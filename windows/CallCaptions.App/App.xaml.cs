using System.Windows;

namespace CallCaptions.App;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var window = new UI.MainWindow();
        MainWindow = window;
        window.Show();
    }
}
