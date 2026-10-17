package course.workflow;

import java.awt.*;
import java.nio.file.*;
import java.util.Properties;

import javax.swing.*;

/**
 * 系统设置的集中编辑界面；元素参数仍由各自侧边栏处理，取消时不提交编辑值。
 *
 * <p>Edits application preferences as a draft; node parameters stay in their own inspectors.
 */
@SuppressWarnings("serial")
final class SystemSettingsPanel extends JPanel {
    final WorkflowApp.Editor editor;
    final SystemSettings settings;
    final JTextField library = new JTextField(), runs = new JTextField(), python = new JTextField();
    final JSpinner workers = new JSpinner(new SpinnerNumberModel(4, 1, 8, 1));
    final AiServicesPanel ai;
    JDialog dialog;

    SystemSettingsPanel(WorkflowApp.Editor editor) throws java.io.IOException {
        super(new BorderLayout(0, 16));
        this.editor = editor;
        settings = new SystemSettings(editor.folderSettings);
        setPreferredSize(new Dimension(800, 510));
        setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));
        setBackground(WorkflowApp.BG);
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(WorkflowApp.label("系统设置", 22, WorkflowApp.INK, true), BorderLayout.NORTH);
        heading.add(
                WorkflowApp.label("应用级选项在此管理；元素参数在右侧配置栏中修改。", 12, WorkflowApp.MUTED, false),
                BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(WorkflowApp.BODY);
        ai = new AiServicesPanel(AiServices.load(settings.load()));
        tabs.addTab("AI服务", ai);
        JPanel storage = form();
        path(storage, "流程库文件夹", library, true);
        path(storage, "运行记录文件夹", runs, true);
        note(storage, "更改流程库后，“打开”和新流程保存使用新的文件夹。<br>已有流程、运行记录和当前正在编辑的流程不会自动迁移。");
        tabs.addTab("文件与记录", storage);
        JPanel execution = form();
        path(execution, "Python 解释器（留空自动查找）", python, false);
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        row.add(new JLabel("同时运行的任务数（1–8）"), BorderLayout.WEST);
        row.add(workers, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        execution.add(row);
        note(
                execution,
                "保存后，下次执行使用新的任务数和解释器。<br>设置1为依次执行；互不依赖的任务可同时运行。<br>自动查找优先使用启动环境配置及程序附带的 Python。");
        tabs.addTab("运行", execution);
        JPanel about = form();
        note(
                about,
                "Flow 工作流 v0.16.1<br>Java："
                        + escape(System.getProperty("java.version"))
                        + "<br>Java 位置："
                        + escape(System.getProperty("java.home"))
                        + "<br><br>办公文件转 PDF 需要 Office 或 LibreOffice。<br>音频提取需要音频组件。<br>Java 编译节点的目标版本和 JDK 路径在元素侧边栏中设置。");
        tabs.addTab("关于与依赖", about);
        add(tabs, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.setOpaque(false);
        JButton cancel = new JButton("取消"), save = new JButton("保存设置");
        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> save());
        bottom.add(cancel);
        bottom.add(save);
        add(bottom, BorderLayout.SOUTH);
        reload();
    }

    void reload() throws java.io.IOException {
        Properties p = settings.load();
        library.setText(settings.path(p, "workflow.library", "flow-library").toString());
        runs.setText(settings.path(p, "execution.runs", "run-history").toString());
        python.setText(settings.python());
        workers.setValue(settings.workers());
        ai.services.clear();
        ai.services.addAll(AiServices.load(p));
        ai.reload();
    }

    /** 禁止执行中提交系统设置；保存成功后关闭对话框。 Commits preferences only while no run is active. */
    void save() {
        try {
            if (editor.executing) throw new java.io.IOException("请等待当前流程结束后再保存系统设置。");
            settings.save(
                    library.getText(),
                    runs.getText(),
                    python.getText(),
                    (Integer) workers.getValue(),
                    java.util.List.copyOf(ai.services));
            editor.log("系统设置已保存，将用于下一次打开或执行。");
            editor.hint("系统设置已保存。");
            dialog.dispose();
        } catch (Exception ex) {
            editor.error("保存系统设置失败", ex);
        }
    }

    JPanel form() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(20, 18, 18, 18));
        p.setBackground(Color.WHITE);
        return p;
    }

    void path(JPanel parent, String label, JTextField field, boolean folder) {
        JPanel block = new JPanel(new BorderLayout(8, 7));
        block.setOpaque(false);
        block.add(new JLabel(label), BorderLayout.NORTH);
        field.setFont(WorkflowApp.BODY);
        block.add(field, BorderLayout.CENTER);
        JButton browse = new JButton("浏览");
        browse.addActionListener(
                e -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setFileSelectionMode(
                            folder ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
                    if (!field.getText().isBlank())
                        chooser.setSelectedFile(new java.io.File(field.getText()));
                    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                        field.setText(chooser.getSelectedFile().getAbsolutePath());
                });
        block.add(browse, BorderLayout.EAST);
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
        parent.add(block);
        parent.add(Box.createVerticalStrut(18));
    }

    void note(JPanel parent, String text) {
        JLabel label = new JLabel("<html>" + text + "</html>");
        label.setFont(WorkflowApp.BODY);
        label.setForeground(WorkflowApp.MUTED);
        label.setAlignmentX(LEFT_ALIGNMENT);
        parent.add(Box.createVerticalStrut(16));
        parent.add(label);
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void show(WorkflowApp.Editor editor) {
        if (editor.executing) {
            editor.hint("请等待流程结束后再修改系统设置。");
            return;
        }
        try {
            var panel = new SystemSettingsPanel(editor);
            JDialog dialog =
                    new JDialog(
                            SwingUtilities.getWindowAncestor(editor),
                            "系统设置",
                            Dialog.ModalityType.APPLICATION_MODAL);
            panel.dialog = dialog;
            dialog.setContentPane(panel);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.pack();
            dialog.setLocationRelativeTo(editor);
            dialog.setVisible(true);
        } catch (Exception ex) {
            editor.error("读取系统设置失败", ex);
        }
    }
}
