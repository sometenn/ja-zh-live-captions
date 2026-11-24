using System.Security.Cryptography;
using System.Text;

namespace CallCaptions.Core;

public interface IKeyVault
{
    string Read(Provider provider);
    void Save(string key, Provider provider);
}

public sealed class DpapiVault : IKeyVault
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("CallCaptions.Windows.KeyVault.v1");
    private readonly string _directory;

    public DpapiVault(string? directory = null)
    {
        _directory = directory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "CallCaptions");
    }

    public string Read(Provider provider)
    {
        string path = FilePath(provider);
        if (!File.Exists(path)) return string.Empty;

        try
        {
            byte[] encrypted = File.ReadAllBytes(path);
            if (encrypted.Length == 0) return string.Empty;
            byte[] plain = ProtectedData.Unprotect(encrypted, Entropy, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(plain);
        }
        catch (Exception ex) when (ex is CryptographicException or IOException or UnauthorizedAccessException)
        {
            throw new CaptionError("无法从本机凭据存储读取密钥。", CaptionErrorKind.General, ex);
        }
    }

    public void Save(string key, Provider provider)
    {
        string path = FilePath(provider);
        if (string.IsNullOrEmpty(key))
        {
            if (File.Exists(path)) File.Delete(path);
            return;
        }

        try
        {
            Directory.CreateDirectory(_directory);
            byte[] plain = Encoding.UTF8.GetBytes(key);
            byte[] encrypted = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);
            File.WriteAllBytes(path, encrypted);
        }
        catch (Exception ex) when (ex is CryptographicException or IOException or UnauthorizedAccessException)
        {
            throw new CaptionError("无法将密钥保存到本机凭据存储。", CaptionErrorKind.General, ex);
        }
    }

    private string FilePath(Provider provider) =>
        Path.Combine(_directory, $"key-{provider.ToString().ToLowerInvariant()}.dat");
}
