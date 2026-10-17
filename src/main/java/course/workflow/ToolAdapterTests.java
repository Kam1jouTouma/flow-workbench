package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class ToolAdapterTests {
    static void run(Path root) throws IOException {
        root = root.toAbsolutePath();
        if (Files.exists(root)) throw new IOException("测试目录必须不存在。");
        Files.createDirectories(root);
        Map<String, Object> queries =
                Map.of(
                        "--help",
                        "Test CLI tool",
                        "--describe",
                        FileCli.describe("batch.rename"),
                        "--capabilities",
                        ElementCapabilities.declaration("batch.rename"));
        Path script = root.resolve("tool test.py");
        Files.writeString(
                script,
                "import json,sys\nsys.stdout.reconfigure(encoding='utf-8')\nq=json.loads("
                        + Json.stringify(Json.stringify(queries))
                        + ")\nx=q.get(sys.argv[1])\nif x is None: sys.exit(2)\nprint(x if isinstance(x,str) else json.dumps(x,ensure_ascii=False))\n");
        Path bat = root.resolve("tool test.bat");
        Files.writeString(
                bat,
                "@echo off\r\nchcp 65001 >nul\r\n\""
                        + ToolLauncher.python()
                        + "\" \"%~dp0tool test.py\" %*\r\nexit /b %errorlevel%\r\n",
                java.nio.charset.StandardCharsets.UTF_8);
        String description = Json.stringify(FileCli.describe("batch.rename")),
                capability = Json.stringify(ElementCapabilities.declaration("batch.rename"));
        Path cs = root.resolve("Probe.cs");
        Files.writeString(
                cs,
                "using System; public class Probe { public static int Main(string[] a) { if(a.Length!=1)return 2; string s; if(a[0]==\"--help\")s=\"Test CLI tool\"; else if(a[0]==\"--describe\")s=@\""
                        + description.replace("\"", "\"\"")
                        + "\"; else if(a[0]==\"--capabilities\")s=@\""
                        + capability.replace("\"", "\"\"")
                        + "\"; else return 2; Console.OutputEncoding=new System.Text.UTF8Encoding(false);Console.WriteLine(s);return 0;} }");
        Path exe = root.resolve("tool test.exe");
        Path compiler = Path.of("C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe");
        if (!Files.isRegularFile(compiler)) throw new IOException("测试EXE编译器不存在。");
        ProcessCommands.run(
                List.of(
                        compiler.toString(),
                        "/nologo",
                        "/target:exe",
                        "/out:" + exe,
                        cs.toString()),
                root,
                30);
        List<Object> results = new ArrayList<>();
        for (Path tool :
                List.of(script, bat, exe, FileTransferRunner.toolPath("rename-tool-v1.jar")))
            results.add(ToolLauncher.probe(tool));
        Path broken = root.resolve("bad.py");
        Files.writeString(broken, "print('not a protocol description')\n");
        boolean rejected = false;
        try {
            ToolLauncher.probe(broken);
        } catch (IOException ex) {
            rejected = true;
        }
        if (!rejected) throw new AssertionError("Invalid declaration accepted");
        Json.write(
                root.resolve("summary.json"),
                Map.of("status", "PASS", "tools", results, "invalidRejected", true));
        System.out.println(
                "PASS adapters: exe, py, bat, jar actual queries; invalid protocol rejected.");
    }
}
