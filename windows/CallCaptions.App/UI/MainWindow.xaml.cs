using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;
using CallCaptions.App.Audio;
using CallCaptions.App.Overlay;
using CallCaptions.Core;

using Forms = System.Windows.Forms;

namespace CallCaptions.App.UI;

public partial class MainWindow : Window
{
    private sealed record FontOption(string Value, string Label);

    private readonly JsonSettingsStore _settingsStore;
    private readonly WindowsOverlayController _overlay;
    private readonly Session _session;
    private readonly Forms.NotifyIcon _tray;
    private bool _openOverlayOnStart;
    private bool _updatingUi;

    public MainWindow()
    {
        InitializeComponent();

        _settingsStore = new JsonSettingsStore();
        var initialSettings = _settingsStore.Load();
        _overlay = new WindowsOverlayController(initialSettings, () => _settingsStore.Save(initialSettings));

        _session = new Session(
            settingsStore: new PreloadedSettingsStore(_settingsStore, initialSettings),
            overlay: _overlay,
            makeCapture: source => new WindowsAudioCapture(source));

        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("开启实时字幕", null, (_, _) => Dispatcher.Invoke(QuickStart));
        menu.Items.Add("打开日语实时字幕", null, (_, _) => Dispatcher.Invoke(ShowHome));
        _tray = new Forms.NotifyIcon {
            Text = "日语实时字幕",
            Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? System.Drawing.SystemIcons.Application,
            ContextMenuStrip = menu,
            Visible = true
        };
        _tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowHome);
        SizeChanged += (_, _) => ArrangePanels();
        ArrangePanels();
        PopulateFonts();
        SyncControlsFromState();

        _session.PropertyChanged += Session_PropertyChanged;
        _overlay.PropertyChanged += Overlay_PropertyChanged;
        Closing += MainWindow_Closing;
    }

    private void ArrangePanels()
    {
        bool open = SettingsCard.Visibility == Visibility.Visible;
        bool sideBySide = open && (ActualWidth > 0 ? ActualWidth : Width) >= 930;
        SettingsColumn.Width = new GridLength(sideBySide ? 390 : 0);
        SettingsRow.Height = open && !sideBySide ? new GridLength(0.85, GridUnitType.Star) : new GridLength(0);
        Grid.SetColumn(SettingsCard, sideBySide ? 1 : 0);
        Grid.SetRow(SettingsCard, sideBySide ? 2 : 3);
        SettingsCard.Margin = sideBySide ? new Thickness(16, 0, 0, 0) : new Thickness(0, 12, 0, 0);
    }

    private async void SaveTranscript_Click(object sender, RoutedEventArgs e)
    {
        if (!_session.HasTranscript) return;
        string snapshot = _session.TranscriptText();
        var dialog = new Microsoft.Win32.SaveFileDialog {
            FileName = $"字幕记录-{DateTime.Now:yyyyMMdd-HHmmss}.txt",
            DefaultExt = ".txt",
            Filter = "文本文件 (*.txt)|*.txt"
        };
        if (dialog.ShowDialog(this) != true) return;
        string temporary = Path.Combine(Path.GetDirectoryName(dialog.FileName)!, Path.GetRandomFileName());
        bool ownsTemporary = false;
        try
        {
            await using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                ownsTemporary = true;
                await using var writer = new StreamWriter(stream, System.Text.Encoding.UTF8);
                await writer.WriteAsync(snapshot);
            }
            File.Move(temporary, dialog.FileName, overwrite: true);
            ownsTemporary = false;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            System.Windows.MessageBox.Show(this, error.Message, "保存记录失败");
        }
        finally
        {
            try { if (ownsTemporary) File.Delete(temporary); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private void ShowHome()
    {
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Show();
        Activate();
    }

    private void QuickStart()
    {
        ShowHome();
        if (_session.Stage == SessionStage.Listening && _session.Mode == SessionMode.Translate) {
            _overlay.Start();
            return;
        }
        if (_session.Stage != SessionStage.Idle) return;
        if (!_session.HasKey && new KeyDialog(_session) { Owner = this }.ShowDialog() != true) return;
        if (!_session.HasKey) return;
        _openOverlayOnStart = true;
        _session.Begin(SessionMode.Translate);
        if (_session.Stage == SessionStage.Idle) _openOverlayOnStart = false;
    }

    private void PopulateFonts()
    {
        var currentLang = XmlLanguage.GetLanguage(System.Globalization.CultureInfo.CurrentUICulture.IetfLanguageTag);
        var options = new List<FontOption> { new(string.Empty, "系统字体") };
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (FontFamily ff in Fonts.SystemFontFamilies)
        {
            string source = ff.Source;
            if (string.IsNullOrWhiteSpace(source) || !seen.Add(source)) continue;

            string label = ff.FamilyNames.TryGetValue(currentLang, out string? localized)
                ? localized
                : ff.FamilyNames.Values.FirstOrDefault() ?? source;
            options.Add(new FontOption(source, label));
        }

        FontFamilyCombo.ItemsSource = options
            .Take(1)
            .Concat(options.Skip(1).OrderBy(o => o.Label, StringComparer.CurrentCultureIgnoreCase))
            .ToList();
    }

    private void SyncControlsFromState()
    {
        _updatingUi = true;
        try
        {
            var appearance = _session.Settings.Appearance;

            SonioxRadio.IsChecked = _session.Provider == Provider.Soniox;
            GeminiRadio.IsChecked = _session.Provider == Provider.Gemini;

            SystemAudioRadio.IsChecked = _session.Source == AudioSource.System;
            MicAudioRadio.IsChecked = _session.Source == AudioSource.Microphone;

            BilingualRadio.IsChecked = _session.DisplayMode == CaptionDisplayMode.Bilingual;
            ChineseOnlyRadio.IsChecked = _session.DisplayMode == CaptionDisplayMode.ChineseOnly;

            FontFamilyCombo.SelectedValue = appearance.FontFamily ?? string.Empty;
            if (FontFamilyCombo.SelectedIndex < 0) FontFamilyCombo.SelectedIndex = 0;

            SourceSizeSlider.Value = appearance.SourceSize;
            TranslationSizeSlider.Value = appearance.TranslationSize;
            MainMaxLinesSlider.Value = Math.Clamp(appearance.MainMaxLines, 1, 8);
            MaxLinesSlider.Value = Math.Clamp(appearance.MaxLines, 1, 4);
            OpacitySlider.Value = appearance.Opacity;

            InkWhiteRadio.IsChecked = appearance.Ink == CaptionInk.White;
            InkMintRadio.IsChecked = appearance.Ink == CaptionInk.Mint;
            InkAmberRadio.IsChecked = appearance.Ink == CaptionInk.Amber;

            UpdateAppearanceLabels();
            UpdateDynamicState();
        }
        finally
        {
            _updatingUi = false;
        }
    }

    private void UpdateAppearanceLabels()
    {
        var appearance = _session.Settings.Appearance;
        SourceSizeLabel.Text = $"{(int)Math.Round(appearance.SourceSize)} pt";
        TranslationSizeLabel.Text = $"{(int)Math.Round(appearance.TranslationSize)} pt";
        MainMaxLinesLabel.Text = $"{Math.Clamp(appearance.MainMaxLines, 1, 8)} 行";
        MaxLinesLabel.Text = $"{Math.Clamp(appearance.MaxLines, 1, 4)} 行";
        OpacityLabel.Text = $"{(int)Math.Round(appearance.Opacity)} %";
    }

    private void UpdateDynamicState()
    {
        bool isIdle = _session.Stage == SessionStage.Idle;
        bool isStopping = _session.Stage == SessionStage.Stopping;
        bool isPicking = _session.Stage == SessionStage.Picking;

        SaveTranscriptButton.IsEnabled = _session.HasTranscript;
        KeyButton.IsEnabled = isIdle;
        ToggleOverlayButton.IsEnabled = !isStopping;
        ToggleOverlayButton.Content = _overlay.Active ? "收起置顶字幕" : "开启置顶字幕";

        PreviewTitleText.Text = isIdle ? "字幕预览" : "实时字幕";

        SonioxRadio.IsEnabled = !isPicking && !isStopping;
        GeminiRadio.IsEnabled = !isPicking && !isStopping;
        SystemAudioRadio.IsEnabled = isIdle;
        MicAudioRadio.IsEnabled = isIdle;
        BilingualRadio.IsEnabled = !isStopping;
        ChineseOnlyRadio.IsEnabled = !isStopping;

        SessionPrivacyText.Text = $"测试收音仅在本机进行。翻译时，所选声音会发送给 {_session.Provider.DisplayName()}。";
        StatusText.Text = string.IsNullOrEmpty(_session.SwitchNotice) ? _session.Status : _session.SwitchNotice;
        StatusText.ToolTip = StatusText.Text;

        ProbeButton.Visibility = isIdle ? Visibility.Visible : Visibility.Collapsed;
        StartButton.Visibility = isIdle ? Visibility.Visible : Visibility.Collapsed;
        StopButton.Visibility = isIdle ? Visibility.Collapsed : Visibility.Visible;
        StopButton.IsEnabled = !isStopping;

        OverlayFrame preview = isIdle
            ? CaptionSample.Sample(_session.DisplayMode)
            : _session.Frame;
        PreviewControl.Update(preview, _session.Settings.Appearance);
    }

    private void Session_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(Session.Stage) && _openOverlayOnStart) {
            if (_session.Stage == SessionStage.Listening) _overlay.Start();
            if (_session.Stage != SessionStage.Picking) _openOverlayOnStart = false;
        }
        _updatingUi = true;
        try
        {
            SonioxRadio.IsChecked = _session.Provider == Provider.Soniox;
            GeminiRadio.IsChecked = _session.Provider == Provider.Gemini;
            SystemAudioRadio.IsChecked = _session.Source == AudioSource.System;
            MicAudioRadio.IsChecked = _session.Source == AudioSource.Microphone;
            BilingualRadio.IsChecked = _session.DisplayMode == CaptionDisplayMode.Bilingual;
            ChineseOnlyRadio.IsChecked = _session.DisplayMode == CaptionDisplayMode.ChineseOnly;
            UpdateDynamicState();
        }
        finally
        {
            _updatingUi = false;
        }
    }

    private void Overlay_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(WindowsOverlayController.Active))
        {
            ToggleOverlayButton.Content = _overlay.Active ? "收起置顶字幕" : "开启置顶字幕";
        }
    }

    private void SettingsButton_Click(object sender, RoutedEventArgs e)
    {
        bool appearance = sender == AppearanceSettingsButton;
        bool open = SettingsCard.Visibility != Visibility.Visible || appearance != (AppearancePanel.Visibility == Visibility.Visible);
        SettingsCard.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
        AppearancePanel.Visibility = appearance ? Visibility.Visible : Visibility.Collapsed;
        SessionPanel.Visibility = appearance ? Visibility.Collapsed : Visibility.Visible;
        SessionSettingsButton.Content = open && !appearance ? "收起会话设置" : "会话设置";
        AppearanceSettingsButton.Content = open && appearance ? "收起字幕外观" : "字幕外观";
        ArrangePanels();
    }

    private void ProviderRadio_Checked(object sender, RoutedEventArgs e)
    {
        if (_updatingUi || !IsLoaded) return;
        Provider next = GeminiRadio.IsChecked == true ? Provider.Gemini : Provider.Soniox;
        _session.SelectProvider(next);
    }

    private void SourceRadio_Checked(object sender, RoutedEventArgs e)
    {
        if (_updatingUi || !IsLoaded) return;
        AudioSource next = MicAudioRadio.IsChecked == true ? AudioSource.Microphone : AudioSource.System;
        _session.SelectSource(next);
    }

    private void DisplayModeRadio_Checked(object sender, RoutedEventArgs e)
    {
        if (_updatingUi || !IsLoaded) return;
        CaptionDisplayMode next = ChineseOnlyRadio.IsChecked == true
            ? CaptionDisplayMode.ChineseOnly
            : CaptionDisplayMode.Bilingual;
        _session.SelectDisplayMode(next);
    }

    private void FontFamilyCombo_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_updatingUi || !IsLoaded) return;
        _session.Settings.Appearance.FontFamily = FontFamilyCombo.SelectedValue as string ?? string.Empty;
        ApplyAppearanceChange();
    }

    private void AppearanceSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_updatingUi || !IsLoaded) return;
        _session.Settings.Appearance.SourceSize = Math.Round(SourceSizeSlider.Value);
        _session.Settings.Appearance.TranslationSize = Math.Round(TranslationSizeSlider.Value);
        _session.Settings.Appearance.MainMaxLines = (int)MainMaxLinesSlider.Value;
        _session.Settings.Appearance.MaxLines = (int)MaxLinesSlider.Value;
        _session.Settings.Appearance.Opacity = Math.Round(OpacitySlider.Value);
        ApplyAppearanceChange();
    }

    private void InkRadio_Checked(object sender, RoutedEventArgs e)
    {
        if (_updatingUi || !IsLoaded) return;
        _session.Settings.Appearance.Ink = InkMintRadio.IsChecked == true
            ? CaptionInk.Mint
            : (InkAmberRadio.IsChecked == true ? CaptionInk.Amber : CaptionInk.White);
        ApplyAppearanceChange();
    }

    private void RestoreDefaultsButton_Click(object sender, RoutedEventArgs e)
    {
        _session.Settings.Appearance.RestoreDefaults();
        _session.SaveSettings();
        SyncControlsFromState();
        _overlay.RefreshAppearance();
    }

    private void ApplyAppearanceChange()
    {
        UpdateAppearanceLabels();
        _session.SaveSettings();
        UpdateDynamicState();
        _overlay.RefreshAppearance();
    }

    private void KeyButton_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new KeyDialog(_session) { Owner = this };
        dialog.ShowDialog();
    }

    private void ToggleOverlayButton_Click(object sender, RoutedEventArgs e)
    {
        if (_overlay.Active) _overlay.Stop();
        else _overlay.Start();
    }

    private void ProbeButton_Click(object sender, RoutedEventArgs e)
    {
        _session.Begin(SessionMode.Probe);
    }

    private void StartButton_Click(object sender, RoutedEventArgs e)
    {
        if (_session.HasKey)
        {
            _session.Begin(SessionMode.Translate);
        }
        else
        {
            var dialog = new KeyDialog(_session) { Owner = this };
            if (dialog.ShowDialog() == true && _session.HasKey)
            {
                _session.Begin(SessionMode.Translate);
            }
        }
    }

    private void StopButton_Click(object sender, RoutedEventArgs e)
    {
        _session.Finish();
    }

    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        _session.PropertyChanged -= Session_PropertyChanged;
        _overlay.PropertyChanged -= Overlay_PropertyChanged;
        _session.Finish();
        _overlay.DisposeWindow();
        _tray.Visible = false;
        _tray.ContextMenuStrip?.Dispose();
        _tray.Dispose();
    }

    private sealed class PreloadedSettingsStore(ISettingsStore inner, AppSettingsData preloaded) : ISettingsStore
    {
        public AppSettingsData Load() => preloaded;
        public void Save(AppSettingsData data) => inner.Save(data);
    }
}
