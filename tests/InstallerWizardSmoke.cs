using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows.Forms;
using System.Diagnostics;
// Drive the actual installer form and its background install worker, without screenshots.
class InstallerWizardSmoke
{
    [STAThread]
    static int Main(string[] args)
    {
        try
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            var assembly = Assembly.LoadFile(Path.GetFullPath(args[0]));
            var type = assembly.GetType("FlowSetup+InstallForm", true);
            var form = (Form)Activator.CreateInstance(type, true);
            var flags = BindingFlags.Instance | BindingFlags.NonPublic;
            var folder = (TextBox)type.GetField("folder", flags).GetValue(form);
            var audio = (CheckBox)type.GetField("audio", flags).GetValue(form);
            var desktop = (CheckBox)type.GetField("desktop", flags).GetValue(form);
            var launch = (CheckBox)type.GetField("launch", flags).GetValue(form);
            var install = (Button)type.GetField("install", flags).GetValue(form);
            var status = (Label)type.GetField("status", flags).GetValue(form);
            if (!desktop.Checked || !launch.Checked || audio.Checked)
                throw new Exception("Unexpected installer defaults");
            folder.Text = Path.GetFullPath(args[1]);
            audio.Checked = false;
            desktop.Checked = true;
            launch.Checked = args.Length > 2 && args[2] == "launch";
            form.ShowInTaskbar = false;
            form.Opacity = 0;
            form.WindowState = FormWindowState.Minimized;
            bool closeAttempted = false;
            int result = 1;
            var clock = Stopwatch.StartNew();
            var timer = new Timer { Interval = 200 };
            timer.Tick += (s, e) =>
            {
                if (args.Length > 2 && args[2] == "close-during" && !closeAttempted &&
                    clock.Elapsed.TotalMilliseconds > 200)
                {
                    closeAttempted = true;
                    form.Close();
                    if (form.IsDisposed)
                    {
                        Console.Error.WriteLine(
                            "FAIL: installer closed while extraction was active");
                        return;
                    }
                    Console.WriteLine("PASS: active installation blocks closing");
                }
                if (clock.Elapsed.TotalSeconds > 180)
                {
                    Console.Error.WriteLine("Installer timeout: " + status.Text);
                    timer.Stop();
                    form.Close();
                    return;
                }
                if (status.Text.StartsWith("安装完成："))
                {
                    Console.WriteLine("WIZARD PASS " + status.Text);
                    result = 0;
                    timer.Stop();
                    form.Close();
                }
            };
            form.Shown += (s, e) =>
            {
                if (args.Length > 2 && args[2] == "cancel")
                {
                    result = 0;
                    Console.WriteLine("CANCEL PASS");
                    form.Close();
                    return;
                }
                timer.Start();
                install.PerformClick();
            };
            Application.Run(form);
            return result;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex);
            return 1;
        }
    }
}
