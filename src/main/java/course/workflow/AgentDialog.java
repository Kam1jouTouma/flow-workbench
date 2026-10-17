package course.workflow;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.*;

/**
 * Agent 的交互层：展示对话和草稿，用户明确应用后才替换画布，并保留打开时的恢复快照。
 *
 * <p>The agent UI previews drafts, applies them explicitly and retains the original editor
 * snapshot.
 */
@SuppressWarnings("serial")
final class AgentDialog extends JDialog {
    final WorkflowApp.Editor editor;
    final WorkflowMcp mcp;
    AgentConversation agent;
    final WorkflowApp.Flow original;
    final Path originalFile;
    final boolean originalDirty;
    final JTextArea conversation = new JTextArea(),
            input = new JTextArea(4, 45),
            preview = new JTextArea();
    final JComboBox<String> services = new JComboBox<>();
    final Map<String, AiServices.Service> serviceMap = new LinkedHashMap<>();
    final JSpinner tokens = new JSpinner(new SpinnerNumberModel(4096, 128, 16384, 128)),
            timeout = new JSpinner(new SpinnerNumberModel(120, 1, 600, 10)),
            rounds = new JSpinner(new SpinnerNumberModel(8, 1, 20, 1));
    final JComboBox<String> tokenParameter =
            new JComboBox<>(new String[] {"max_completion_tokens", "max_tokens"});
    final JButton send = new JButton("发送需求"),
            stop = new JButton("停止"),
            apply = new JButton("应用草稿到画布"),
            undo = new JButton("还原原画布"),
            reset = new JButton("新对话");
    final JLabel state = new JLabel("描述你想搭建的流程。");
    AtomicBoolean cancelled;
    SwingWorker<String, String> worker;
    volatile Thread taskThread;

    AgentDialog(WorkflowApp.Editor editor) throws java.io.IOException {
        super(
                SwingUtilities.getWindowAncestor(editor),
                "流程助手",
                Dialog.ModalityType.APPLICATION_MODAL);
        this.editor = editor;
        original = Subgraphs.copy(editor.flow);
        originalFile = editor.currentFile;
        originalDirty = editor.dirty;
        mcp = new WorkflowMcp(editor.flow, editor.folderSettings);
        agent = new AgentConversation(mcp);
        setSize(1040, 720);
        setMinimumSize(new Dimension(850, 620));
        setLocationRelativeTo(editor);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        root.setBackground(WorkflowApp.BG);
        setContentPane(root);
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(
                WorkflowApp.label("流程助手 · 通过MCP搭建流程", 20, WorkflowApp.INK, true),
                BorderLayout.NORTH);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.setOpaque(false);
        controls.add(new JLabel("服务"));
        services.setPreferredSize(new Dimension(230, 30));
        controls.add(services);
        controls.add(new JLabel("Token"));
        controls.add(tokens);
        controls.add(new JLabel("超时秒"));
        controls.add(timeout);
        controls.add(new JLabel("轮数"));
        controls.add(rounds);
        controls.add(tokenParameter);
        top.add(controls, BorderLayout.CENTER);
        top.add(new JLabel("对话和草稿配置会发给所选服务；MCP仅编辑草稿。实际文件处理仍由执行按钮启动。"), BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        for (var service : AiServices.load(new SystemSettings(editor.folderSettings).load())) {
            serviceMap.put(service.id(), service);
            services.addItem(service.id());
        }
        services.setRenderer(
                new DefaultListCellRenderer() {
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean selected,
                            boolean focus) {
                        super.getListCellRendererComponent(list, value, index, selected, focus);
                        var service = serviceMap.get(value);
                        setText(
                                service == null
                                        ? "请先在系统设置添加AI服务"
                                        : service.name() + " · " + service.model());
                        return this;
                    }
                });
        conversation.setEditable(false);
        conversation.setLineWrap(true);
        conversation.setWrapStyleWord(true);
        conversation.setFont(WorkflowApp.BODY);
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        preview.setFont(WorkflowApp.BODY);
        JSplitPane split =
                new JSplitPane(
                        JSplitPane.HORIZONTAL_SPLIT,
                        new JScrollPane(conversation),
                        new JScrollPane(preview));
        split.setResizeWeight(.68);
        root.add(split, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setFont(WorkflowApp.BODY);
        bottom.add(new JScrollPane(input), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.setOpaque(false);
        bottom.add(state, BorderLayout.NORTH);
        actions.add(reset);
        actions.add(stop);
        actions.add(send);
        actions.add(apply);
        actions.add(undo);
        bottom.add(actions, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
        stop.setEnabled(false);
        apply.setEnabled(false);
        undo.setEnabled(false);
        send.setEnabled(!serviceMap.isEmpty());
        send.addActionListener(e -> send());
        stop.addActionListener(e -> cancel());
        apply.addActionListener(e -> apply());
        undo.addActionListener(
                e -> {
                    editor.clearNavigation();
                    editor.flow = Subgraphs.copy(original);
                    editor.currentFile = originalFile;
                    editor.dirty = originalDirty;
                    editor.select(null);
                    editor.resetView();
                    editor.refresh();
                    undo.putClientProperty("applied", null);
                    undo.setEnabled(false);
                    state.setText("原画布已还原。");
                });
        reset.addActionListener(
                e -> {
                    agent = new AgentConversation(mcp);
                    conversation.setText("");
                    state.setText("已开始新对话，当前草稿保留。");
                });
        addWindowListener(
                new java.awt.event.WindowAdapter() {
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        if (worker != null) {
                            cancel();
                            state.setText("正在停止，请稍候再关闭。");
                        } else dispose();
                    }
                });
        refreshDraft();
        append("助手", "例如：把一个目录中的Word逐个转成PDF，再保存到另一个目录。请提供实际路径；没有路径也可以先搭建待配置草稿。");
    }

    void append(String who, String text) {
        conversation.append(who + "：\n" + text + "\n\n");
        conversation.setCaretPosition(conversation.getDocument().getLength());
    }

    void refreshDraft() {
        StringBuilder text = new StringBuilder("草稿：" + mcp.draft.title + "\n\n");
        for (var n : mcp.draft.nodes)
            text.append(n.name).append(" · ").append(n.kind.label).append('\n');
        text.append("\n连线：").append(mcp.draft.edges.size()).append("\n\n待处理项：\n");
        var issues = WorkflowMcp.issues(mcp.draft);
        text.append(issues.isEmpty() ? "基本配置与连接检查通过。" : String.join("\n", issues));
        preview.setText(text.toString());
        preview.setCaretPosition(0);
    }

    void busy(boolean busy) {
        send.setEnabled(!busy && !serviceMap.isEmpty());
        stop.setEnabled(busy);
        reset.setEnabled(!busy);
        apply.setEnabled(!busy && mcp.revision > 0);
        services.setEnabled(!busy);
        tokens.setEnabled(!busy);
        timeout.setEnabled(!busy);
        rounds.setEnabled(!busy);
        tokenParameter.setEnabled(!busy);
        undo.setEnabled(!busy && undo.getClientProperty("applied") != null);
    }

    /**
     * 模型请求在后台执行，进度和最终草稿通过 SwingWorker 返回事件线程。
     *
     * <p>Runs model requests in a worker and delivers progress and completion to the Swing EDT.
     */
    void send() {
        String demand = input.getText().strip();
        if (demand.isBlank()) return;
        var service = serviceMap.get(services.getSelectedItem());
        if (service == null) return;
        int max = (Integer) tokens.getValue(),
                seconds = (Integer) timeout.getValue(),
                maxRounds = (Integer) rounds.getValue();
        String field = (String) tokenParameter.getSelectedItem();
        input.setText("");
        append("你", demand);
        cancelled = new AtomicBoolean();
        busy(true);
        worker =
                new SwingWorker<>() {
                    protected String doInBackground() throws Exception {
                        taskThread = Thread.currentThread();
                        try {
                            return agent.turn(
                                    service,
                                    demand,
                                    max,
                                    seconds,
                                    maxRounds,
                                    field,
                                    cancelled,
                                    this::publish);
                        } finally {
                            taskThread = null;
                        }
                    }

                    protected void process(List<String> messages) {
                        for (String message : messages) {
                            state.setText(message);
                            if (message.startsWith("调用MCP")) append("工具", message);
                        }
                    }

                    protected void done() {
                        try {
                            append("助手", get());
                            state.setText(mcp.revision > 0 ? "草稿已准备，可检查并应用。" : "模型尚未修改草稿，可补充需求。");
                        } catch (Exception ex) {
                            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                            append(
                                    "提示",
                                    cause instanceof java.io.IOException
                                            ? cause.getMessage()
                                            : "助手响应或工具调用格式不符合规范，请重试。");
                            state.setText("本次对话结束。");
                        } finally {
                            worker = null;
                            busy(false);
                            refreshDraft();
                        }
                    }
                };
        worker.execute();
    }

    /** 标记循环停止并中断当前等待；已完成的草稿修改保留。 Cancels waiting while retaining draft edits. */
    void cancel() {
        if (cancelled != null) cancelled.set(true);
        Thread thread = taskThread;
        if (thread != null) thread.interrupt();
        stop.setEnabled(false);
    }

    /**
     * 应用的是草稿副本，并清空保存路径以便另存；这里不执行任何流程节点。
     *
     * <p>Applies a copied draft with no current save path; no workflow nodes execute here.
     */
    void apply() {
        if (editor.executing) return;
        editor.clearNavigation();
        editor.flow = Subgraphs.copy(mcp.draft);
        editor.currentFile = null;
        editor.dirty = true;
        editor.select(null);
        editor.resetView();
        editor.refresh();
        editor.log("流程助手草稿已应用，请检查配置并保存。");
        undo.putClientProperty("applied", true);
        undo.setEnabled(true);
        state.setText("已应用；关闭助手即可查看画布。");
    }

    static void show(WorkflowApp.Editor editor) {
        if (editor.executing) {
            editor.hint("请等待流程结束后再使用助手。");
            return;
        }
        try {
            new AgentDialog(editor).setVisible(true);
        } catch (Exception ex) {
            editor.error("打开流程助手失败", ex);
        }
    }
}
