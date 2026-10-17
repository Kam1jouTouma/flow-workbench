// 将外部 FFmpeg 包装为统一工具协议。 / Adapts FFmpeg to the shared tool protocol.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

// Independent EXE protocol adapter; FFmpeg and ffprobe perform actual decoding.
public static class AudioExtractTool
{
    static readonly JavaScriptSerializer Json =
        new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024, RecursionLimit = 64 };
    static readonly string[] Formats = { "mp4", "mkv", "mov",  "avi", "webm", "mp3",
                                         "wav", "m4a", "flac", "aac", "ogg" };
    static readonly string Root = AppDomain.CurrentDomain.BaseDirectory;
    static Dictionary<string, object> D(params object[] pairs)
    {
        var result = new Dictionary<string, object>();
        for (int i = 0; i < pairs.Length; i += 2)
            result[(string)pairs[i]] = pairs[i + 1];
        return result;
    }
    static Dictionary<string, object> M(object value)
    {
        var map = value as Dictionary<string, object>;
        if (map == null)
            throw new Exception("Expected object");
        return map;
    }
    static object[] A(object value)
    {
        var list = value as IEnumerable;
        if (list == null || value is string || value is IDictionary)
            throw new Exception("Expected array");
        return list.Cast<object>().ToArray();
    }
    static string S(Dictionary<string, object> map, string name)
    {
        object value;
        if (!map.TryGetValue(name, out value) || !(value is string))
            throw new Exception("Missing string " + name);
        return (string)value;
    }
    static object Read(string path)
    {
        return Json.DeserializeObject(File.ReadAllText(path, Encoding.UTF8));
    }
    static void Write(string path, object value)
    {
        File.WriteAllText(path, Json.Serialize(value), new UTF8Encoding(false));
    }
    static string Hash(string path)
    {
        using (var sha = SHA256.Create()) using (
            var stream = File.OpenRead(path)) return BitConverter.ToString(sha.ComputeHash(stream))
            .Replace("-", "")
            .ToLowerInvariant();
    }
    static object DescriptionPort(string name, string type, string cardinality)
    {
        return D("name", name, "label", name, "types", new[] { type }, "cardinality", cardinality,
                 "required", true);
    }
    static object CapabilityPort(string name, string type, string cardinality, string[] formats)
    {
        return D("name", name, "dataTypes", new[] { type }, "cardinality", cardinality, "formats",
                 formats, "validation", "IDENTIFY");
    }
    static object Description()
    {
        return D("protocolVersion", 1, "id", "media.extract_audio", "name", "提取MP3音频",
                 "version", "1.0.0", "description",
                 "Extract the first audio stream of each local media file to same-name MP3 using " +
                 "FFmpeg. No audio stream fails the complete stage. bitrate: 128/192/256/320 kbps.",
                 "inputs",
                 new[] { DescriptionPort("data", "FILE", "COLLECTION"),
                         DescriptionPort("source", "JSON", "SINGLE") },
                 "outputs",
                 new[] { DescriptionPort("data", "FILE", "COLLECTION"),
                         DescriptionPort("source", "JSON", "SINGLE") },
                 "parameters",
                 new[] { D("name", "bitrate", "label", "音质kbps", "type", "string", "default",
                           "192", "required", false) });
    }
    static object Capabilities()
    {
        return D("capabilityVersion", 1, "toolId", "media.extract_audio", "toolVersion", "1.0.0",
                 "implementation",
                 File.Exists(Path.Combine(Root, "ffmpeg", "ffmpeg.exe")) &&
                         File.Exists(Path.Combine(Root, "ffmpeg", "ffprobe.exe"))
                     ? "AVAILABLE"
                     : "UNAVAILABLE",
                 "operation", "CONVERT", "inputs",
                 new[] { CapabilityPort("data", "FILE", "COLLECTION", Formats),
                         CapabilityPort("source", "JSON", "SINGLE", new[] { "json" }) },
                 "outputs",
                 new[] { CapabilityPort("data", "FILE", "COLLECTION", new[] { "mp3" }),
                         CapabilityPort("source", "JSON", "SINGLE", new[] { "json" }) },
                 "knownExtensions", Formats);
    }
    static string Quote(string value)
    {
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value)
        {
            if (c == '\\')
            {
                slashes++;
                continue;
            }
            if (c == '"')
            {
                result.Append('\\', slashes * 2 + 1);
                result.Append(c);
            }
            else
            {
                result.Append('\\', slashes);
                result.Append(c);
            }
            slashes = 0;
        }
        result.Append('\\', slashes * 2);
        return result.Append('"').ToString();
    }
    static string Run(string program, string[] args, int seconds)
    {
        using (var process = new Process())
        {
            process.StartInfo = new ProcessStartInfo(
                program,
                string.Join(" ", args.Select(Quote))) { UseShellExecute = false,
                                                        CreateNoWindow = true,
                                                        RedirectStandardOutput = true,
                                                        RedirectStandardError = true,
                                                        StandardOutputEncoding = Encoding.UTF8,
                                                        StandardErrorEncoding = Encoding.UTF8 };
            process.Start();
            var output = Task.Run(() => process.StandardOutput.ReadToEnd());
            var error = Task.Run(() => process.StandardError.ReadToEnd());
            if (!process.WaitForExit(seconds * 1000))
            {
                process.Kill();
                throw new Exception("FFmpeg/ffprobe timed out");
            }
            Task.WaitAll(new Task[] { output, error }, 5000);
            if (!output.IsCompleted || !error.IsCompleted)
                throw new Exception("Media log capture timed out");
            if (output.Result.Length > 1024 * 1024 || error.Result.Length > 1024 * 1024)
                throw new Exception("Media log too large");
            if (process.ExitCode != 0)
                throw new Exception("Media command failed: " + error.Result);
            return output.Result;
        }
    }
    static Dictionary<string, object> Probe(string path)
    {
        return M(Json.DeserializeObject(
            Run(Path.Combine(Root, "ffmpeg", "ffprobe.exe"),
                new[] { "-v", "error", "-protocol_whitelist", "file,pipe", "-show_streams",
                        "-show_format", "-of", "json", "-i", path },
                60)));
    }
    static bool FormatMatches(string ext, string format)
    {
        var found = format.Split(',');
        if (new[] { "mp4", "m4a", "mov" }.Contains(ext))
            return found.Contains("mov") || found.Contains("mp4");
        if (new[] { "mkv", "webm" }.Contains(ext))
            return found.Contains("matroska") || found.Contains("webm");
        return found.Contains(ext);
    }
    public static int Main(string[] args)
    {
        Console.OutputEncoding = new UTF8Encoding(false);
        try
        {
            if (args.Length == 1)
            {
                switch (args[0])
                {
                case "--help":
                case "-h":
                    Console.WriteLine("MP3 extraction EXE\n--describe | --capabilities | " +
                                      "--version | --input JSON --config JSON --output " +
                                      "empty-directory\nRequires tools/ffmpeg/ffmpeg.exe and " +
                                      "ffprobe.exe; first audio stream; no network input.");
                    return 0;
                case "--describe":
                    Console.WriteLine(Json.Serialize(Description()));
                    return 0;
                case "--capabilities":
                    Console.WriteLine(Json.Serialize(Capabilities()));
                    return 0;
                case "--version":
                    Console.WriteLine("1.0.0");
                    return 0;
                }
            }
            if (args.Length != 6)
                throw new Exception("Expected input/config/output exactly once");
            var options = new Dictionary<string, string>();
            for (int i = 0; i < 6; i += 2)
            {
                if (!new[] { "--input", "--config", "--output" }.Contains(args[i]) ||
                    options.ContainsKey(args[i]))
                    throw new Exception("Unknown or duplicate option");
                options[args[i]] = args[i + 1];
            }
            foreach (string path in options.Values)
                if (!Path.IsPathRooted(path))
                    throw new Exception("Paths must be absolute");
            string stage = options["--output"];
            if (!Directory.Exists(stage) || Directory.EnumerateFileSystemEntries(stage).Any())
                throw new Exception("Output directory must be empty");
            var request = M(Read(options["--input"]));
            var config = M(Read(options["--config"]));
            if (Convert.ToInt32(request["protocolVersion"]) != 1 ||
                Convert.ToInt32(config["protocolVersion"]) != 1)
                throw new Exception("Protocol version unsupported");
            var parameters = M(config["parameters"]);
            if (parameters.Keys.Any(k => k != "bitrate"))
                throw new Exception("Unknown audio parameter");
            string bitrate = parameters.ContainsKey("bitrate") ? S(parameters, "bitrate") : "192";
            if (!new[] { "128", "192", "256", "320" }.Contains(bitrate))
                throw new Exception("Invalid bitrate");
            var ports = M(request["inputs"]);
            var data = M(ports["data"]);
            var manifest = M(ports["source"]);
            if (S(data, "type") != "FILE" || S(manifest, "type") != "JSON")
                throw new Exception("Expected FILE data and JSON source");
            var manifests = A(manifest["items"]);
            if (manifests.Length != 1)
                throw new Exception("Expected one source manifest");
            var files = A(data["items"]);
            var sources = A(M(Read(S(M(manifests[0]), "path")))["files"]);
            if (files.Length == 0 || files.Length != sources.Length || files.Length > 500)
                throw new Exception("Invalid source count (1..500)");
            var outputItems = new List<object>();
            var outputSources = new List<object>();
            var checks = new List<object>();
            for (int i = 0; i < files.Length; i++)
            {
                string sourcePath = S(M(files[i]), "path");
                if (!Path.IsPathRooted(sourcePath) || !File.Exists(sourcePath))
                    throw new Exception("Input file not readable");
                var source = new Dictionary<string, object>(M(sources[i]));
                if (Hash(sourcePath) != S(source, "sha256"))
                    throw new Exception("Input hash mismatch");
                string ext = Path.GetExtension(sourcePath).TrimStart('.').ToLowerInvariant();
                if (!Formats.Contains(ext))
                    throw new Exception("Unsupported media extension: " + ext);
                var probe = Probe(sourcePath);
                string format = S(M(probe["format"]), "format_name");
                if (!FormatMatches(ext, format))
                    throw new Exception("File extension does not match media container: " +
                                        sourcePath);
                var streams = A(probe["streams"]);
                if (!streams.Any(s => S(M(s), "codec_type") == "audio"))
                    throw new Exception("No audio stream: " + sourcePath);
                string relative = S(source, "relativePath").Replace('\\', '/');
                if (relative.StartsWith("/") || relative.Contains(":") ||
                    relative.Split('/').Any(s => s == ".." || s == "." || s.Length == 0))
                    throw new Exception("Invalid relative filename");
                relative = Path.ChangeExtension(relative, ".mp3").Replace('\\', '/');
                string artifact = "payloads/" + i + "/" + relative;
                string destination =
                    Path.Combine(stage, artifact.Replace('/', Path.DirectorySeparatorChar));
                Directory.CreateDirectory(Path.GetDirectoryName(destination));
                Run(Path.Combine(Root, "ffmpeg", "ffmpeg.exe"),
                    new[] { "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                            "-protocol_whitelist", "file,pipe", "-i", sourcePath, "-map", "0:a:0",
                            "-vn", "-c:a", "libmp3lame", "-b:a", bitrate + "k", "-f", "mp3",
                            destination },
                    540);
                var verified = Probe(destination);
                if (!A(verified["streams"])
                         .Any(s => S(M(s), "codec_type") == "audio" &&
                                   S(M(s), "codec_name") == "mp3"))
                    throw new Exception("Generated audio validation failed");
                object inherited;
                if (!source.TryGetValue("originals", out inherited))
                    inherited = new[] { D("path", S(source, "originalPath"), "sha256",
                                          S(source, "sha256")) };
                source["originals"] = inherited;
                source["relativePath"] = relative;
                source["sha256"] = Hash(destination);
                outputItems.Add(D("path", artifact, "mediaType", "audio/mpeg"));
                outputSources.Add(source);
                checks.Add(
                    D("source", sourcePath, "detectedContainer", format, "outputCodec", "mp3"));
            }
            Write(Path.Combine(stage, "source.json"), D("files", outputSources));
            Write(Path.Combine(stage, "warnings.json"), D("warnings", new object[0]));
            Write(Path.Combine(stage, "media-checks.json"), D("files", checks));
            Write(Path.Combine(stage, "result.json"),
                  D("protocolVersion", 1, "runId", request["runId"], "nodeId", request["nodeId"],
                    "outputs",
                    D("data", D("type", "FILE", "items", outputItems), "source",
                      D("type", "JSON", "items",
                        new[] { D("path", "source.json", "mediaType", "application/json") }))));
            Console.WriteLine("Completed: media.extract_audio");
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error.Message);
            return 4;
        }
    }
}
