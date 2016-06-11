package org.eplight.medirc.client.image;

import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Paint;

public class RectImageFragment implements ImageFragment {

    private RectSelectPainter painter;
    private Point2D start;
    private Point2D end;
    private double zoom;
    private Paint thePaint;

    public RectImageFragment(Point2D start, Point2D end, double zoom, Paint thePaint) {
        this.start = start;
        this.end = end;
        this.zoom = zoom;
        this.thePaint = thePaint;
        this.painter = new RectSelectPainter();
    }

    @Override
    public void paint(GraphicsContext gc, double canvasZoom) {
        canvasZoom /= zoom;
        painter.paint(gc, start.multiply(canvasZoom), end.multiply(canvasZoom), thePaint);
    }
}
