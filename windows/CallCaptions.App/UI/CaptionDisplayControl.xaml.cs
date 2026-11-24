using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using CallCaptions.Core;

namespace CallCaptions.App.UI;

public partial class CaptionDisplayControl : UserControl
{
    private static readonly FontFamily DefaultUiFont = new("Microsoft YaHei UI, Yu Gothic UI, Segoe UI");

    private OverlayFrame _frame = CaptionPresentation.IdleFrame(CaptionDisplayMode.Bilingual);
    private CaptionAppearanceSettings _appearance = new();

    public double CornerRadiusValue
    {
        get => RootBorder.CornerRadius.TopLeft;
        set => RootBorder.CornerRadius = new CornerRadius(value);
    }

    public CaptionDisplayControl()
    {
        InitializeComponent();
        SizeChanged += (_, _) => Render();
        Loaded += (_, _) => Render();
    }

    public void Update(OverlayFrame frame, CaptionAppearanceSettings appearance)
    {
        _frame = frame;
        _appearance = appearance;
        Render();
    }

    public void RefreshAppearance(CaptionAppearanceSettings appearance)
    {
        _appearance = appearance;
        Render();
    }

    private void Render()
    {
        byte alpha = (byte)Math.Clamp((int)Math.Round(_appearance.Opacity / 100.0 * 255.0), 64, 255);
        RootBorder.Background = new SolidColorBrush(Color.FromArgb(alpha, 11, 33, 36));

        double padX = ActualWidth > 0 ? Math.Clamp(ActualWidth * 0.04, 10.0, 20.0) : 16.0;
        double padY = ActualHeight > 0 ? Math.Clamp(ActualHeight * 0.10, 6.0, 14.0) : 12.0;
        RootBorder.Padding = new Thickness(padX, padY, padX, padY);
        TopText.Margin = new Thickness(0, 0, 0, ActualHeight is > 0 and < 95 ? 2 : 6);

        if (_frame.Banner is { } banner)
        {
            BannerText.Text = banner.Text;
            BannerText.Foreground = banner.Tone == OverlayBannerTone.Warning
                ? new SolidColorBrush(Color.FromRgb(255, 217, 90))
                : new SolidColorBrush(Color.FromRgb(190, 230, 224));
            BannerText.Visibility = Visibility.Visible;
        }
        else
        {
            BannerText.Visibility = Visibility.Collapsed;
        }

        double availableWidth = Math.Max(40.0, ActualWidth - padX * 2.0);
        FontFamily fontFamily = ResolveFontFamily();
        Brush inkBrush = ResolveInkBrush();

        TopText.Visibility = Visibility.Collapsed;
        BottomText.Visibility = Visibility.Collapsed;
        ProbeStatusText.Visibility = Visibility.Collapsed;
        ProbeMeterText.Visibility = Visibility.Collapsed;

        switch (_frame.Style)
        {
            case OverlayStyle.Bilingual bilingual:
            {
                string topFitted = FitLine(bilingual.Top, fontFamily, _appearance.SourceSize, FontWeights.Normal, availableWidth);
                string bottomFitted = FitLine(bilingual.Bottom, fontFamily, _appearance.TranslationSize, FontWeights.SemiBold, availableWidth);

                TopText.FontFamily = fontFamily;
                TopText.FontSize = _appearance.SourceSize;
                TopText.FontWeight = FontWeights.Normal;
                TopText.Foreground = new SolidColorBrush(Color.FromArgb(209, 255, 255, 255));
                TopText.Text = topFitted;
                TopText.Visibility = string.IsNullOrEmpty(topFitted) ? Visibility.Collapsed : Visibility.Visible;

                BottomText.FontFamily = fontFamily;
                BottomText.FontSize = _appearance.TranslationSize;
                BottomText.FontWeight = FontWeights.SemiBold;
                BottomText.Foreground = inkBrush;
                BottomText.Text = bottomFitted;
                BottomText.Visibility = Visibility.Visible;
                break;
            }
            case OverlayStyle.ChineseOnly chineseOnly:
            {
                string fitted = FitLine(chineseOnly.Text, fontFamily, _appearance.TranslationSize, FontWeights.SemiBold, availableWidth);
                BottomText.FontFamily = fontFamily;
                BottomText.FontSize = _appearance.TranslationSize;
                BottomText.FontWeight = FontWeights.SemiBold;
                BottomText.Foreground = chineseOnly.IsPlaceholder
                    ? new SolidColorBrush(Color.FromArgb(170, 255, 255, 255))
                    : inkBrush;
                BottomText.Text = fitted;
                BottomText.Visibility = Visibility.Visible;
                break;
            }
            case OverlayStyle.Probe probe:
            {
                ProbeStatusText.Text = probe.Status;
                ProbeStatusText.Visibility = Visibility.Visible;
                ProbeMeterText.Text = probe.Meter;
                ProbeMeterText.Visibility = Visibility.Visible;
                break;
            }
        }
    }

    private FontFamily ResolveFontFamily()
    {
        if (string.IsNullOrWhiteSpace(_appearance.FontFamily)) return DefaultUiFont;
        try
        {
            return new FontFamily(_appearance.FontFamily);
        }
        catch
        {
            return DefaultUiFont;
        }
    }

    private Brush ResolveInkBrush() => _appearance.Ink switch
    {
        CaptionInk.Mint => new SolidColorBrush(Color.FromRgb(166, 247, 212)),
        CaptionInk.Amber => new SolidColorBrush(Color.FromRgb(255, 222, 143)),
        _ => Brushes.White
    };

    private string FitLine(string raw, FontFamily fontFamily, double fontSize, FontWeight weight, double maxWidth)
    {
        if (string.IsNullOrEmpty(raw)) return string.Empty;
        double dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var typeface = new Typeface(fontFamily, FontStyles.Normal, weight, FontStretches.Normal);

        return CaptionTextLayout.FitRecentPhrase(raw, candidate =>
        {
            var ft = new FormattedText(
                candidate,
                CultureInfo.CurrentUICulture,
                FlowDirection.LeftToRight,
                typeface,
                fontSize,
                Brushes.White,
                dpi);
            return ft.WidthIncludingTrailingWhitespace <= maxWidth;
        });
    }
}
