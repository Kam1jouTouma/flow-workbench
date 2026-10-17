package course.workflow;

import java.nio.file.*;
import java.util.*;

public class ScriptFormatsSmoke {
    public static void main(String[] args) throws Exception {
        Path app = FileTransferRunner.toolPath(""), root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        Path input = root.resolve("original.txt");
        Files.writeString(input, "adapter formats");
        for (String file :
                List.of(
                        "script-templates/Java/java-copy-demo.jar",
                        "script-templates/BAT/copy_wrapper.bat",
                        args[1])) {
            var record = ScriptTools.inspect(app.resolve(file));
            var f = new WorkflowApp.Flow();
            var a = BatchTests.input(f, input, false, "txt");
            var n = f.add(WorkflowApp.Kind.SCRIPT, 300, 100);
            ScriptTools.configure(n, record);
            f.connect(a, n);
            Path folder = root.resolve(FileCli.extension(Path.of(file)));
            BatchTests.output(f, n, folder);
            var result = BatchRunner.execute(f, root.resolve("runs"), w -> {});
            if (result.files().size() != 1
                    || !Files.readString(result.files().get(0).destination())
                            .equals("adapter formats")) throw new AssertionError(file);
            System.out.println(
                    "PASS imported " + FileCli.extension(Path.of(file)) + " actual execution");
        }
    }
}
