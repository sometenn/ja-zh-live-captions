using System.ComponentModel;
using System.Runtime.CompilerServices;
using CallCaptions.App.UI;
using CallCaptions.Core;

namespace CallCaptions.App.Overlay;

public sealed class WindowsOverlayController : IOverlayController, INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    private readonly AppSettingsData _settings;
    private readonly Action _saveSettings;
    private OverlayWindow? _window;
    private OverlayFrame _frame = CaptionPresentation.IdleFrame(CaptionDisplayMode.Bilingual);
    private bool _active;

    public bool Active
    {
        get => _active;
        private set
        {
            if (_active == value) return;
            _active = value;
            OnPropertyChanged();
        }
    }

    public OverlayFrame Frame
    {
        get => _frame;
        private set
        {
            if (Equals(_frame, value)) return;
            _frame = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(PreviewFrame));
        }
    }

    public OverlayFrame PreviewFrame
    {
        get
        {
            foreach (CaptionDisplayMode mode in Enum.GetValues<CaptionDisplayMode>())
            {
                if (Equals(Frame, CaptionPresentation.IdleFrame(mode)))
                {
                    return CaptionSample.Sample(mode);
                }
            }
            return Frame;
        }
    }

    public WindowsOverlayController(AppSettingsData settings, Action saveSettings)
    {
        _settings = settings;
        _saveSettings = saveSettings;
    }

    public void Prepare() { }

    public void Draw(OverlayFrame next)
    {
        Frame = next;
    }

    public void RefreshAppearance()
    {
        _window?.RefreshAppearance();
    }

    public void Start()
    {
        if (_window is null)
        {
            _window = new OverlayWindow(this, _settings, _saveSettings);
            _window.UserClosed += (_, _) => Active = false;
        }

        _window.Show();
        _window.Activate();
        Active = true;
    }

    public void Stop()
    {
        _window?.HideWindow();
        Active = false;
    }

    public void DisposeWindow()
    {
        if (_window is null) return;
        _window.ForceClose();
        _window = null;
        Active = false;
    }

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
}

public static class CaptionSample
{
    public static OverlayFrame Sample(CaptionDisplayMode mode) => mode switch
    {
        CaptionDisplayMode.Bilingual => new OverlayFrame(
            null,
            new OverlayStyle.Bilingual("今日はどんな一日でしたか？", "你今天过得怎么样？")),
        CaptionDisplayMode.ChineseOnly => new OverlayFrame(
            null,
            new OverlayStyle.ChineseOnly("你今天过得怎么样？", false)),
        _ => new OverlayFrame(
            null,
            new OverlayStyle.Bilingual("今日はどんな一日でしたか？", "你今天过得怎么样？"))
    };
}
