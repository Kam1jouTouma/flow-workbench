// 先提取程序，再初始化独立数据目录；卸载依赖明确的安装清单。
// Extract the application, initialize separate user data, and register the exact install instance.
using System;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;
using System.ComponentModel;
[assembly:AssemblyTitle("Flow Workbench Preview Installer")]
[assembly:AssemblyVersion("0.16.1.0")]
[assembly:AssemblyFileVersion("0.16.1.0")]
class FlowSetup
{
    const string Version = "0.16.1";
    [STAThread]
    static int Main(string[] args)
    {
        try
        {
            if (args.Length == 2 && args[0] == "--extract-only")
            {
                string dir = Path.GetFullPath(args[1]);
                if (Directory.Exists(dir) || File.Exists(dir))
                    throw new Exception("Test extraction target must not exist.");
                Extract(dir);
                Initialize(dir, Path.Combine(dir, "data"));
                Console.WriteLine("Installed test payload: " + dir);
                return 0;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallForm());
            return 0;
        }
        catch (Exception e)
        {
            Console.Error.WriteLine(e.ToString());
            return 1;
        }
    }
    public static void Extract(string directory)
    {
        Directory.CreateDirectory(directory);
        string prefix = Path.GetFullPath(directory).TrimEnd(Path.DirectorySeparatorChar) +
                        Path.DirectorySeparatorChar;
        using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(
                   "payload.zip")) using (var zip = new ZipArchive(stream, ZipArchiveMode.Read))
        {
            foreach (var entry in zip.Entries)
            {
                string target = Path.GetFullPath(Path.Combine(
                    directory, entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
                if (!target.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                    throw new Exception("Unsafe archive path");
                if (entry.FullName.EndsWith("/"))
                {
                    Directory.CreateDirectory(target);
                    continue;
                }
                Directory.CreateDirectory(Path.GetDirectoryName(target));
                using (var input = entry.Open()) using (
                    var output = new FileStream(target, FileMode.CreateNew, FileAccess.Write))
                {
                    input.CopyTo(output);
                }
            }
        }
    }
    public static string Run(string file, string arguments, string cwd)
    {
        var start = new ProcessStartInfo(file, arguments) {
            WorkingDirectory = cwd, UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        using (var process = Process.Start(start))
        {
            var output = process.StandardOutput.ReadToEndAsync();
            var error = process.StandardError.ReadToEndAsync();
            if (!process.WaitForExit(300000))
            {
                process.Kill();
                throw new Exception("Initialization timeout");
            }
            string text = output.Result + error.Result;
            if (process.ExitCode != 0)
                throw new Exception(text);
            return text;
        }
    }
    static string Q(string path)
    {
        return "\"" + path + "\"";
    }
    public static void Initialize(string app, string data)
    {
        File.WriteAllText(Path.Combine(app, "install-data.path"), data,
                          new System.Text.UTF8Encoding(false));
        WriteInstallation(app, data, "");
        Run(Path.Combine(app, "runtime", "java", "bin", "java.exe"),
            "-Djava.awt.headless=true -jar " + Q(Path.Combine(app, "workflow-ui-v0.16.1.jar")) +
                " --initialize-package " + Q(app) + " " + Q(data),
            app);
    }
    public static void WriteInstallation(string app, string data, string shortcut)
    {
        string id = "FlowWorkbench-" + Version + "-" +
                    BitConverter
                        .ToString(System.Security.Cryptography.SHA256.Create().ComputeHash(
                            System.Text.Encoding.UTF8.GetBytes(app)))
                        .Replace("-", "")
                        .ToLowerInvariant();
        var serializer = new System.Web.Script.Serialization.JavaScriptSerializer();
        File.WriteAllText(
            Path.Combine(app, "installation.json"),
            serializer.Serialize(new { app = Path.GetFullPath(app), data = Path.GetFullPath(data),
                                       version = Version, registryId = id, shortcut = shortcut }),
            new System.Text.UTF8Encoding(false));
    }
    public static void RegisterUninstall(string app)
    {
        var serializer = new System.Web.Script.Serialization.JavaScriptSerializer();
        var record = serializer.Deserialize<System.Collections.Generic.Dictionary<string, object>>(
            File.ReadAllText(Path.Combine(app, "installation.json")));
        using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(
                   @"Software\Microsoft\Windows\CurrentVersion\Uninstall\" +
                   (string)record["registryId"]))
        {
            key.SetValue("DisplayName", "Flow 工作流 " + Version);
            key.SetValue("DisplayVersion", Version);
            key.SetValue("InstallLocation", app);
            key.SetValue("UninstallString",
                         "powershell.exe -NoProfile -ExecutionPolicy Bypass -File " +
                             Q(Path.Combine(app, "uninstall.ps1")));
            key.SetValue("NoModify", 1);
            key.SetValue("NoRepair", 1);
        }
    }
    public static void Shortcut(string app)
    {
        Type shellType = Type.GetTypeFromProgID("WScript.Shell");
        dynamic shell = Activator.CreateInstance(shellType);
        string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        string link = Path.Combine(desktop, "Flow 工作流预览版 " + Version + ".lnk");
        int i = 1;
        while (File.Exists(link))
            link = Path.Combine(desktop, "Flow 工作流预览版 " + Version + " (" + (i++) + ").lnk");
        dynamic shortcut = shell.CreateShortcut(link);
        shortcut.TargetPath = Path.Combine(app, "launch.cmd");
        shortcut.WorkingDirectory = app;
        shortcut.IconLocation = Path.Combine(app, "runtime", "java", "bin", "javaw.exe") + ",0";
        shortcut.Description = "Flow课程工作流预览版";
        shortcut.Save();
        WriteInstallation(app, File.ReadAllText(Path.Combine(app, "install-data.path")).Trim(),
                          link);
    }
    class InstallForm : Form
    {
        TextBox folder = new TextBox();
        CheckBox audio = new CheckBox(), desktop = new CheckBox(), launch = new CheckBox();
        Button install = new Button();
        Label status = new Label();
        ProgressBar progress = new ProgressBar();
        public InstallForm()
        {
            Text = "Flow 工作流预览版安装 " + Version;
            ClientSize = new Size(600, 410);
            Font = new Font("Microsoft YaHei", 10);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            Controls.Add(new Label { Text = "安装 Flow 工作流预览版",
                                     Font = new Font(Font.FontFamily, 16, FontStyle.Bold),
                                     AutoSize = true, Location = new Point(24, 22) });
            Controls.Add(new Label { Text = "内置 Java、Python 和中文字体；无需管理员权限。",
                                     AutoSize = true, Location = new Point(24, 64) });
            Controls.Add(
                new Label { Text = "安装位置", AutoSize = true, Location = new Point(24, 107) });
            folder.SetBounds(24, 134, 458, 28);
            folder.Text = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "FlowWorkbench");
            Controls.Add(folder);
            var browse = new Button { Text = "浏览", Location = new Point(490, 132),
                                      Size = new Size(82, 31) };
            browse.Click += (s, e) =>
            {
                using (var dialog = new FolderBrowserDialog())
                {
                    dialog.Description = "选择安装根目录，用户数据放在其data文件夹";
                    if (dialog.ShowDialog() == DialogResult.OK)
                        folder.Text = dialog.SelectedPath;
                }
            };
            Controls.Add(browse);
            audio.Text = "安装音频组件（联网下载，约 100 MB）";
            audio.SetBounds(24, 184, 540, 26);
            Controls.Add(audio);
            desktop.Text = "创建桌面快捷方式";
            desktop.Checked = true;
            desktop.SetBounds(24, 217, 540, 26);
            Controls.Add(desktop);
            launch.Text = "安装完成后打开程序";
            launch.Checked = true;
            launch.SetBounds(24, 250, 540, 26);
            Controls.Add(launch);
            status.Text = "流程、脚本和测试输出单独存放，重复安装不会覆盖用户数据。";
            status.SetBounds(24, 286, 548, 28);
            Controls.Add(status);
            progress.SetBounds(24, 319, 548, 16);
            progress.Style = ProgressBarStyle.Marquee;
            progress.Visible = false;
            Controls.Add(progress);
            FormClosing += (s, e) =>
            {
                if (progress.Visible)
                {
                    e.Cancel = true;
                    status.Text = "安装正在进行，请等待完成后关闭。";
                }
            };
            install.Text = "安装";
            install.SetBounds(432, 354, 140, 34);
            install.Click += Begin;
            Controls.Add(install);
        }
        void Begin(object sender, EventArgs e)
        {
            string home;
            try
            {
                home = Path.GetFullPath(folder.Text);
                if (home.Contains("\"") || home.Contains("%") || home.Contains("!"))
                    throw new Exception("Invalid folder");
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }
            bool wantAudio = audio.Checked, wantDesktop = desktop.Checked,
                 wantLaunch = launch.Checked;
            install.Enabled = false;
            folder.Enabled = false;
            audio.Enabled = false;
            desktop.Enabled = false;
            launch.Enabled = false;
            progress.Visible = true;
            var worker = new BackgroundWorker();
            worker.WorkerReportsProgress = true;
            string app = "";
            worker.ProgressChanged += (s, p) => status.Text = (string)p.UserState;
            worker.DoWork += (s, p) =>
            {
                Directory.CreateDirectory(home);
                app = Path.Combine(home, Version);
                int n = 1;
                while (Directory.Exists(app) || File.Exists(app))
                    app = Path.Combine(home, Version + " (" + (n++) + ")");
                worker.ReportProgress(0, "正在解压程序和运行环境…");
                Extract(app);
                worker.ReportProgress(0, "正在生成示例流程和脚本模板…");
                Initialize(app, Path.Combine(home, "data"));
                if (wantAudio)
                {
                    worker.ReportProgress(0, "正在从原始发布站下载音频组件…");
                    Run("powershell.exe",
                        "-NoProfile -ExecutionPolicy Bypass -File " +
                            Q(Path.Combine(app, "install-audio.ps1")),
                        app);
                }
                if (wantDesktop)
                    Shortcut(app);
                RegisterUninstall(app);
            };
            worker.RunWorkerCompleted += (s, p) =>
            {
                progress.Visible = false;
                if (p.Error != null)
                {
                    status.Text = "安装未完成，可重新安装到新版本目录。";
                    MessageBox.Show(p.Error.Message, "安装失败");
                    install.Enabled = true;
                    return;
                }
                status.Text = "安装完成：" + app;
                install.Text = "完成";
                install.Enabled = true;
                install.Click -= Begin;
                install.Click += (a, b) => Close();
                if (wantLaunch)
                    Process.Start(new ProcessStartInfo(
                        "powershell.exe",
                        "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File " +
                            Q(Path.Combine(app, "launch.ps1"))) { UseShellExecute = false,
                                                                  CreateNoWindow = true,
                                                                  WorkingDirectory = app });
            };
            worker.RunWorkerAsync();
        }
    }
}
