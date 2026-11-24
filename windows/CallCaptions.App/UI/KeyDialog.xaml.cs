using System.Windows;
using CallCaptions.Core;

namespace CallCaptions.App.UI;

public partial class KeyDialog : Window
{
    private readonly Session _session;

    public KeyDialog(Session session)
    {
        _session = session;
        InitializeComponent();

        string providerName = _session.Provider.DisplayName();
        Title = $"{providerName} 密钥";
        TitleText.Text = $"{providerName} 密钥";
        HintText.Text = _session.HasKey
            ? $"输入新的 {providerName} API 密钥以替换当前密钥"
            : $"输入 {providerName} API 密钥";

        if (_session.HasKey)
        {
            RemoveButton.Visibility = Visibility.Visible;
        }

        if (_session.Provider == Provider.Gemini)
        {
            GeminiInfoPanel.Visibility = Visibility.Visible;
            GeminiModelText.Text = GeminiService.Model;
        }

        Loaded += (_, _) => KeyBox.Focus();
    }

    private void KeyBox_PasswordChanged(object sender, RoutedEventArgs e)
    {
        SaveButton.IsEnabled = !string.IsNullOrWhiteSpace(KeyBox.Password);
    }

    private void SaveButton_Click(object sender, RoutedEventArgs e)
    {
        Store(KeyBox.Password);
    }

    private void RemoveButton_Click(object sender, RoutedEventArgs e)
    {
        Store(string.Empty);
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private void Store(string value)
    {
        try
        {
            _session.SaveKey(value);
            DialogResult = true;
            Close();
        }
        catch (Exception ex)
        {
            ErrorText.Text = ex.Message;
            ErrorText.Visibility = Visibility.Visible;
        }
    }
}
