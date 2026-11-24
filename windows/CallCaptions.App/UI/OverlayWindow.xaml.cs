using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using CallCaptions.App.Overlay;
using CallCaptions.Core;

namespace CallCaptions.App.UI;

public partial class OverlayWindow : Window
{
    public event EventHandler? UserClosed;

    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint
    {
        public int X;
        public int Y;
    }

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out NativePoint lpPoint);

    private readonly WindowsOverlayController _controller;
    private readonly AppSettingsData _settings;
    private readonly Action _saveSettings;
    private bool _allowClose;

    private Point _dragStartCursorDip;
    private double _dragStartLeft;
    private double _dragStartTop;
    private double _dragStartWidth;
    private double _dragStartHeight;

    public OverlayWindow(WindowsOverlayController controller, AppSettingsData settings, Action saveSettings)
    {
        _controller = controller;
        _settings = settings;
        _saveSettings = saveSettings;

        InitializeComponent();
        ApplySavedBounds();

        _controller.PropertyChanged += Controller_PropertyChanged;
        LocationChanged += (_, _) => PersistBounds();
        SizeChanged += (_, _) => PersistBounds();
        Closing += OverlayWindow_Closing;

        DisplayControl.Update(_controller.PreviewFrame, _settings.Appearance);
    }

    public void RefreshAppearance()
    {
        DisplayControl.RefreshAppearance(_settings.Appearance);
    }

    public void HideWindow()
    {
        if (IsVisible) Hide();
    }

    public void ForceClose()
    {
        _allowClose = true;
        _controller.PropertyChanged -= Controller_PropertyChanged;
        Close();
    }

    private void Controller_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(WindowsOverlayController.PreviewFrame) or nameof(WindowsOverlayController.Frame))
        {
            DisplayControl.Update(_controller.PreviewFrame, _settings.Appearance);
        }
    }

    private void ApplySavedBounds()
    {
        var bounds = _settings.OverlayBounds;
        Width = Math.Max(MinWidth, bounds.Width);
        Height = Math.Max(MinHeight, bounds.Height);

        Rect workArea = SystemParameters.WorkArea;
        if (bounds.Left is double left && bounds.Top is double top
            && left >= workArea.Left - 50 && left <= workArea.Right - 100
            && top >= workArea.Top - 50 && top <= workArea.Bottom - 50)
        {
            Left = left;
            Top = top;
        }
        else
        {
            Left = workArea.Left + (workArea.Width - Width) / 2.0;
            Top = workArea.Bottom - Height - 48.0;
        }
    }

    private void PersistBounds()
    {
        if (!IsLoaded) return;
        _settings.OverlayBounds.Left = Left;
        _settings.OverlayBounds.Top = Top;
        _settings.OverlayBounds.Width = Width;
        _settings.OverlayBounds.Height = Height;
        _saveSettings();
    }

    private void Window_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed)
        {
            DragMove();
        }
    }

    private Point GetCursorDip()
    {
        GetCursorPos(out NativePoint pt);
        DpiScale dpi = VisualTreeHelper.GetDpi(this);
        return new Point(pt.X / dpi.DpiScaleX, pt.Y / dpi.DpiScaleY);
    }

    private void Resize_DragStarted(object sender, DragStartedEventArgs e)
    {
        _dragStartCursorDip = GetCursorDip();
        _dragStartLeft = Left;
        _dragStartTop = Top;
        _dragStartWidth = Width;
        _dragStartHeight = Height;
    }

    private void ApplyResize(bool left, bool right, bool top, bool bottom)
    {
        Point cur = GetCursorDip();
        double dx = cur.X - _dragStartCursorDip.X;
        double dy = cur.Y - _dragStartCursorDip.Y;

        if (left)
        {
            double newWidth = Math.Max(MinWidth, _dragStartWidth - dx);
            Left = _dragStartLeft + (_dragStartWidth - newWidth);
            Width = newWidth;
        }
        else if (right)
        {
            Width = Math.Max(MinWidth, _dragStartWidth + dx);
        }

        if (top)
        {
            double newHeight = Math.Max(MinHeight, _dragStartHeight - dy);
            Top = _dragStartTop + (_dragStartHeight - newHeight);
            Height = newHeight;
        }
        else if (bottom)
        {
            Height = Math.Max(MinHeight, _dragStartHeight + dy);
        }
    }

    private void ResizeLeft_DragDelta(object sender, DragDeltaEventArgs e) => ApplyResize(left: true, right: false, top: false, bottom: false);
    private void ResizeRight_DragDelta(object sender, DragDeltaEventArgs e) => ApplyResize(left: false, right: true, top: false, bottom: false);
    private void ResizeTop_DragDelta(object sender, DragDeltaEventArgs e) => ApplyResize(left: false, right: false, top: true, bottom: false);
    private void ResizeBottom_DragDelta(object sender, DragDeltaEventArgs e) => ApplyResize(left: false, right: false, top: false, bottom: true);
    private void ResizeTopLeft_DragDelta(object sender, DragDeltaEventArgs e) => ApplyResize(left: true, right: false, top: true, bottom: false);
    private void ResizeTopRight_DragDelta(object sender, DragDeltaEventArgs e) => ApplyResize(left: false, right: true, top: true, bottom: false);
    private void ResizeBottomLeft_DragDelta(object sender, DragDeltaEventArgs e) => ApplyResize(left: true, right: false, top: false, bottom: true);
    private void ResizeBottomRight_DragDelta(object sender, DragDeltaEventArgs e) => ApplyResize(left: false, right: true, top: false, bottom: true);

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        Hide();
        UserClosed?.Invoke(this, EventArgs.Empty);
    }

    private void OverlayWindow_Closing(object? sender, CancelEventArgs e)
    {
        if (_allowClose) return;
        e.Cancel = true;
        Hide();
        UserClosed?.Invoke(this, EventArgs.Empty);
    }
}
