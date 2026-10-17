// 通过标准输入输出传输密钥，仅交给当前 Windows 用户的 DPAPI。
// Protect secrets with current-user DPAPI; never pass the secret on the command line.
using System;
using System.IO;
using System.Text;
using System.Security.Cryptography;
// Binary pipes only: no secrets in arguments, environment, files or diagnostics.
class KeyProtector
{
    static int Main(string[] args)
    {
        byte[] input = null, result = null;
        try
        {
            if (args.Length != 1 || (args[0] != "protect" && args[0] != "unprotect"))
                return 2;
            using (var stream = Console.OpenStandardInput()) using (var memory = new MemoryStream())
            {
                byte[] block = new byte[1024];
                int n;
                try
                {
                    while ((n = stream.Read(block, 0, block.Length)) > 0)
                    {
                        if (memory.Length + n > 32768)
                            return 2;
                        memory.Write(block, 0, n);
                    }
                    input = memory.ToArray();
                    Array.Clear(memory.GetBuffer(), 0, (int)memory.Length);
                }
                finally
                {
                    Array.Clear(block, 0, block.Length);
                }
            }
            byte[] entropy = Encoding.UTF8.GetBytes("FlowWorkbench.AiService.Key.v1");
            result = args[0] == "protect"
                         ? ProtectedData.Protect(input, entropy, DataProtectionScope.CurrentUser)
                         : ProtectedData.Unprotect(input, entropy, DataProtectionScope.CurrentUser);
            using (var output = Console.OpenStandardOutput())
            {
                output.Write(result, 0, result.Length);
                output.Flush();
            }
            return 0;
        }
        catch
        {
            Console.Error.WriteLine("Key protection unavailable for this Windows account.");
            return 3;
        }
        finally
        {
            if (input != null)
                Array.Clear(input, 0, input.Length);
            if (result != null)
                Array.Clear(result, 0, result.Length);
        }
    }
}
