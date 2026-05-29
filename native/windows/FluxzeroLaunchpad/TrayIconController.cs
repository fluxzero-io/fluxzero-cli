using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Timers;

namespace Fluxzero.Launchpad;

public sealed class TrayIconController : IDisposable
{
    private const int IconSize = 32;
    private const int RotationStep = 12;
    private const int RestingStep = 120;
    private const uint IconId = 1;
    private const uint CallbackMessage = WmApp + 1;
    private const uint WmApp = 0x8000;
    private const uint WmCommand = 0x0111;
    private const uint WmLButtonUp = 0x0202;
    private const uint WmRButtonUp = 0x0205;
    private const uint WmLButtonDblClk = 0x0203;
    private const uint NimAdd = 0x00000000;
    private const uint NimModify = 0x00000001;
    private const uint NimDelete = 0x00000002;
    private const uint NifMessage = 0x00000001;
    private const uint NifIcon = 0x00000002;
    private const uint NifTip = 0x00000004;
    private const uint NifInfo = 0x00000010;
    private const uint NiifWarning = 0x00000002;
    private const uint MfString = 0x00000000;
    private const uint MfSeparator = 0x00000800;
    private const uint MfGrayed = 0x00000001;
    private const uint TpmRightButton = 0x0002;
    private const int CreateProjectCommand = 1001;
    private const int RefreshCommand = 1002;
    private const int SettingsCommand = 1003;
    private const int AboutCommand = 1004;
    private const int QuitCommand = 1005;

    private readonly Action showCreate;
    private readonly Action refresh;
    private readonly Action showSettings;
    private readonly Action showAbout;
    private readonly Action quit;
    private readonly Bitmap sourceBitmap;
    private readonly System.Timers.Timer timer = new(120);
    private readonly WndProcDelegate wndProc;
    private readonly string className = $"FluxzeroLaunchpadTray{Guid.NewGuid():N}";
    private IntPtr hwnd;
    private IntPtr currentIcon;
    private string status = "Preparing Fluxzero Launchpad";
    private int rotation;
    private bool isBusy;
    private bool stopping;
    private int stopTarget;
    private bool disposed;

    public TrayIconController(Action showCreate, Action refresh, Action showSettings, Action showAbout, Action quit)
    {
        this.showCreate = showCreate;
        this.refresh = refresh;
        this.showSettings = showSettings;
        this.showAbout = showAbout;
        this.quit = quit;
        wndProc = WndProc;
        hwnd = CreateMessageWindow();
        sourceBitmap = LoadSourceBitmap();
        currentIcon = CreateIcon(0);
        timer.Elapsed += (_, _) => AdvanceAnimation();
        AddIcon();
    }

    public void SetStatus(string value)
    {
        status = string.IsNullOrWhiteSpace(value) ? "Fluxzero Launchpad" : value;
    }

    public void SetBusy(bool busy)
    {
        isBusy = busy;
        stopping = false;
        timer.Stop();
        if (rotation != 0)
        {
            rotation = 0;
            SetIcon(rotation);
        }
    }

    public void ShowError(string message)
    {
        var data = BaseData();
        data.uFlags = NifInfo;
        data.szInfoTitle = "Could not complete the Fluxzero action";
        data.szInfo = Trim(message, 255);
        data.dwInfoFlags = NiifWarning;
        Shell_NotifyIcon(NimModify, ref data);
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;
        timer.Stop();
        var data = BaseData();
        Shell_NotifyIcon(NimDelete, ref data);
        DestroyIcon(currentIcon);
        sourceBitmap.Dispose();
        timer.Dispose();
        DestroyWindow(hwnd);
        UnregisterClass(className, GetModuleHandle(null));
    }

    private void AddIcon()
    {
        var data = BaseData();
        data.uFlags = NifMessage | NifIcon | NifTip;
        Shell_NotifyIcon(NimAdd, ref data);
    }

    private void ModifyIcon(uint flags)
    {
        if (disposed)
        {
            return;
        }

        var data = BaseData();
        data.uFlags = flags;
        Shell_NotifyIcon(NimModify, ref data);
    }

    private NotifyIconData BaseData() => new()
    {
        cbSize = (uint)Marshal.SizeOf<NotifyIconData>(),
        hWnd = hwnd,
        uID = IconId,
        uCallbackMessage = CallbackMessage,
        hIcon = currentIcon,
        szTip = "Fluxzero Launchpad"
    };

    private void AdvanceAnimation()
    {
        if (!isBusy && !stopping)
        {
            timer.Stop();
            return;
        }

        rotation = Normalize(rotation + RotationStep);
        SetIcon(rotation);

        if (stopping && rotation == stopTarget)
        {
            stopping = false;
            timer.Stop();
        }
    }

    private void SetIcon(int degrees)
    {
        var previous = currentIcon;
        currentIcon = CreateIcon(degrees);
        ModifyIcon(NifIcon);
        DestroyIcon(previous);
    }

    private IntPtr WndProc(IntPtr window, uint message, IntPtr wParam, IntPtr lParam)
    {
        if (message == CallbackMessage)
        {
            var mouseMessage = unchecked((uint)lParam.ToInt64());
            if (mouseMessage is WmLButtonUp or WmRButtonUp)
            {
                ShowMenu();
                return IntPtr.Zero;
            }
            if (mouseMessage == WmLButtonDblClk)
            {
                showCreate();
                return IntPtr.Zero;
            }
        }

        if (message == WmCommand)
        {
            switch (wParam.ToInt32() & 0xFFFF)
            {
                case CreateProjectCommand:
                    showCreate();
                    break;
                case RefreshCommand:
                    refresh();
                    break;
                case SettingsCommand:
                    showSettings();
                    break;
                case AboutCommand:
                    showAbout();
                    break;
                case QuitCommand:
                    quit();
                    break;
            }
            return IntPtr.Zero;
        }

        return DefWindowProc(window, message, wParam, lParam);
    }

    private void ShowMenu()
    {
        var menu = CreatePopupMenu();
        try
        {
            AppendMenu(menu, MfString, CreateProjectCommand, "Create Project...");
            AppendMenu(menu, MfSeparator, 0, null);
            AppendMenu(menu, MfString | MfGrayed, 0, status);
            if (!status.Contains("up to date", StringComparison.OrdinalIgnoreCase))
            {
                AppendMenu(menu, MfString, RefreshCommand, "Refresh Fluxzero CLI");
            }
            AppendMenu(menu, MfString, SettingsCommand, "Settings");
            AppendMenu(menu, MfString, AboutCommand, "About Fluxzero Launchpad");
            AppendMenu(menu, MfSeparator, 0, null);
            AppendMenu(menu, MfString, QuitCommand, "Quit");
            GetCursorPos(out var point);
            SetForegroundWindow(hwnd);
            TrackPopupMenu(menu, TpmRightButton, point.X, point.Y, 0, hwnd, IntPtr.Zero);
        }
        finally
        {
            DestroyMenu(menu);
        }
    }

    private IntPtr CreateMessageWindow()
    {
        var instance = GetModuleHandle(null);
        var windowClass = new WindowClassEx
        {
            cbSize = (uint)Marshal.SizeOf<WindowClassEx>(),
            lpfnWndProc = wndProc,
            hInstance = instance,
            lpszClassName = className
        };
        RegisterClassEx(ref windowClass);
        return CreateWindowEx(0, className, "Fluxzero Launchpad Tray", 0, 0, 0, 0, 0, IntPtr.Zero, IntPtr.Zero, instance, IntPtr.Zero);
    }

    private IntPtr CreateIcon(float degrees)
    {
        using var canvas = new Bitmap(IconSize, IconSize, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using (var graphics = Graphics.FromImage(canvas))
        {
            graphics.Clear(Color.Transparent);
            graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
            graphics.TranslateTransform(IconSize / 2f, IconSize / 2f);
            graphics.RotateTransform(degrees);
            graphics.TranslateTransform(-IconSize / 2f, -IconSize / 2f);
            graphics.DrawImage(sourceBitmap, new Rectangle(0, 0, IconSize, IconSize));
        }

        return canvas.GetHicon();
    }

    private static Bitmap LoadSourceBitmap()
    {
        var template = Path.Combine(AppContext.BaseDirectory, "Assets", "FluxzeroMenuBarTemplate.png");
        if (File.Exists(template))
        {
            return RecolorTemplate(new Bitmap(template), Color.FromArgb(34, 34, 34));
        }

        var png = Path.Combine(AppContext.BaseDirectory, "Assets", "fluxzero.png");
        if (File.Exists(png))
        {
            return new Bitmap(png);
        }

        var ico = Path.Combine(AppContext.BaseDirectory, "Assets", "fluxzero.ico");
        if (File.Exists(ico))
        {
            using var icon = new Icon(ico, IconSize, IconSize);
            return icon.ToBitmap();
        }

        var fallback = new Bitmap(IconSize, IconSize);
        using var graphics = Graphics.FromImage(fallback);
        graphics.Clear(Color.Transparent);
        using var brush = new SolidBrush(Color.Black);
        System.Drawing.Point[] points = [
            new System.Drawing.Point(16, 2),
            new System.Drawing.Point(29, 9),
            new System.Drawing.Point(29, 23),
            new System.Drawing.Point(16, 30),
            new System.Drawing.Point(3, 23),
            new System.Drawing.Point(3, 9)
        ];
        graphics.FillPolygon(brush, points);
        return fallback;
    }

    private static Bitmap RecolorTemplate(Bitmap source, Color color)
    {
        var result = new Bitmap(source.Width, source.Height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        for (var y = 0; y < source.Height; y++)
        {
            for (var x = 0; x < source.Width; x++)
            {
                var pixel = source.GetPixel(x, y);
                var alpha = pixel.A;
                if (alpha == 0)
                {
                    result.SetPixel(x, y, Color.Transparent);
                    continue;
                }

                result.SetPixel(x, y, Color.FromArgb(alpha, color));
            }
        }

        source.Dispose();
        return result;
    }

    private static int NextRestingRotation(int current) =>
        Normalize(((current / RestingStep) + 1) * RestingStep);

    private static int Normalize(int degrees) =>
        ((degrees % 360) + 360) % 360;

    private static string Trim(string value, int length) =>
        value.Length > length ? value[..length] : value;

    private delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyIconData
    {
        public uint cbSize;
        public IntPtr hWnd;
        public uint uID;
        public uint uFlags;
        public uint uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string szTip;
        public uint dwState;
        public uint dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string szInfo;
        public uint uTimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string szInfoTitle;
        public uint dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WindowClassEx
    {
        public uint cbSize;
        public uint style;
        public WndProcDelegate lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
        public IntPtr hIconSm;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point
    {
        public int X;
        public int Y;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern bool Shell_NotifyIcon(uint dwMessage, ref NotifyIconData lpData);

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr hIcon);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern ushort RegisterClassEx(ref WindowClassEx lpWndClass);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool UnregisterClass(string lpClassName, IntPtr hInstance);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateWindowEx(
        uint dwExStyle,
        string lpClassName,
        string lpWindowName,
        uint dwStyle,
        int x,
        int y,
        int nWidth,
        int nHeight,
        IntPtr hWndParent,
        IntPtr hMenu,
        IntPtr hInstance,
        IntPtr lpParam);

    [DllImport("user32.dll")]
    private static extern bool DestroyWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? lpModuleName);

    [DllImport("user32.dll")]
    private static extern IntPtr CreatePopupMenu();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool AppendMenu(IntPtr hMenu, uint uFlags, int uIDNewItem, string? lpNewItem);

    [DllImport("user32.dll")]
    private static extern bool DestroyMenu(IntPtr hMenu);

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out Point lpPoint);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool TrackPopupMenu(IntPtr hMenu, uint uFlags, int x, int y, int nReserved, IntPtr hWnd, IntPtr prcRect);
}
