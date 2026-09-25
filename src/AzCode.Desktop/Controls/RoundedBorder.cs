using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace AzCode.Desktop.Controls;

/// <summary>
/// 支持四角独立圆角的容器，用于复刻消息气泡的单角「尾巴」。
/// CornerRadii 约定：Left=左上、Top=右上、Right=右下、Bottom=左下。
/// </summary>
public sealed class RoundedBorder : Border
{
    public static readonly DependencyProperty CornerRadiiProperty = DependencyProperty.Register(
        nameof(CornerRadii), typeof(Thickness), typeof(RoundedBorder),
        new FrameworkPropertyMetadata(new Thickness(0), FrameworkPropertyMetadataOptions.AffectsRender));

    public Thickness CornerRadii
    {
        get => (Thickness)GetValue(CornerRadiiProperty);
        set => SetValue(CornerRadiiProperty, value);
    }

    protected override void OnRender(DrawingContext dc)
    {
        var w = ActualWidth;
        var h = ActualHeight;
        if (w <= 0 || h <= 0) return;

        if (Background != null)
            dc.DrawGeometry(Background, null, Build(w, h));

        var stroke = BorderThickness.Left;
        if (stroke > 0 && stroke < w && stroke < h && BorderBrush != null)
        {
            var pen = new Pen(BorderBrush, stroke) { LineJoin = PenLineJoin.Round };
            pen.Freeze();
            var inset = stroke / 2;
            var geo = Build(w - stroke, h - stroke);
            dc.PushTransform(new TranslateTransform(inset, inset));
            dc.DrawGeometry(null, pen, geo);
            dc.Pop();
        }
    }

    private Geometry Build(double w, double h)
    {
        var radii = CornerRadii;
        var max = System.Math.Min(w, h) / 2;
        var tl = Clamp(radii.Left, max);
        var tr = Clamp(radii.Top, max);
        var br = Clamp(radii.Right, max);
        var bl = Clamp(radii.Bottom, max);

        var geo = new StreamGeometry();
        using (var ctx = geo.Open())
        {
            ctx.BeginFigure(new Point(tl, 0), true, true);
            ctx.LineTo(new Point(w - tr, 0), true, false);
            Arc(ctx, new Point(w, tr), tr);

            ctx.LineTo(new Point(w, h - br), true, false);
            Arc(ctx, new Point(w - br, h), br);

            ctx.LineTo(new Point(bl, h), true, false);
            Arc(ctx, new Point(0, h - bl), bl);

            ctx.LineTo(new Point(0, tl), true, false);
            Arc(ctx, new Point(tl, 0), tl);
        }
        geo.Freeze();
        return geo;
    }

    private static void Arc(StreamGeometryContext ctx, Point to, double r)
    {
        if (r <= 0.01) return;
        ctx.ArcTo(to, new Size(r, r), 0, false, SweepDirection.Clockwise, true, false);
    }

    private static double Clamp(double r, double max) => r < 0 ? 0 : (r > max ? max : r);
}
