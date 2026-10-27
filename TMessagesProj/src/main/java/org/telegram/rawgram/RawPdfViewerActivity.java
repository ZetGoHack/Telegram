package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in PDF viewer: pages of android.graphics.pdf.PdfRenderer in a vertical list, pinch / double-tap zoom,
 * page indicator. Inspired by exteraGram's pdf/ui/PdfViewerActivity, written from scratch (no text selection).
 * Heap: pages are rendered at the screen width times the zoom (at most 2x, at most 8 Mpx per page); a byte-limited
 * LRU keeps a few bitmaps and everything is recycled when the screen closes.
 */
public class RawPdfViewerActivity extends BaseFragment {

    private static final int MENU_EXTERNAL = 1;
    private static final float MAX_ZOOM = 2f;
    private static final int MAX_PIXELS = 8_000_000;
    private static final int CACHE_BYTES = 64 * 1024 * 1024;

    private final File file;
    private final String title;

    private DispatchQueue queue;
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private final Object rendererLock = new Object();
    private int[] pageWidth;
    private int[] pageHeight;
    private volatile boolean closed;

    private FrameLayout root;
    private HorizontalScrollView scroller;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private PagesAdapter adapter;
    private RadialProgressView progress;
    private TextView indicator;
    private int viewWidth;
    private float zoom = 1f;
    private final Runnable hideIndicator = () -> indicator.animate().alpha(0f).setDuration(200).start();

    private final Map<Integer, Boolean> wanted = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> pending = new ConcurrentHashMap<>();
    private final LruCache<Long, Bitmap> cache = new LruCache<Long, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public RawPdfViewerActivity(File file, String title) {
        this.file = file;
        this.title = title;
    }

    @Override
    public boolean onFragmentCreate() {
        queue = new DispatchQueue("rawPdf");
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_EXTERNAL) {
                    RawPdfViewer.openExternal(file, title, getParentActivity());
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_EXTERNAL, R.drawable.msg_openin, "Открыть в другом приложении");

        root = new ScaleFrame(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(true);
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateIndicator();
            }
        });
        scroller.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroller, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        scroller.setVisibility(View.INVISIBLE);

        progress = new RadialProgressView(context);
        root.addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        indicator = new TextView(context);
        indicator.setTextSize(14);
        indicator.setTextColor(0xFFFFFFFF);
        indicator.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        indicator.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), 0x99000000));
        indicator.setAlpha(0f);
        root.addView(indicator, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 24));

        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int w = root.getWidth();
            if (w > 0 && w != viewWidth) {
                int old = viewWidth;
                viewWidth = w;
                if (old == 0) {
                    open();
                } else if (adapter != null) {
                    applyZoom(zoom);
                }
            }
        });
        return root;
    }

    private void open() {
        queue.postRunnable(() -> {
            String error = null;
            try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(descriptor);
                int count = renderer.getPageCount();
                int[] w = new int[count];
                int[] h = new int[count];
                for (int i = 0; i < count && !closed; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    w[i] = Math.max(1, page.getWidth());
                    h[i] = Math.max(1, page.getHeight());
                    page.close();
                }
                pageWidth = w;
                pageHeight = h;
            } catch (SecurityException e) {
                error = "PDF защищён паролем";
            } catch (Throwable e) {
                FileLog.e(e);
                error = "Не удалось открыть PDF";
            }
            String message = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (closed) {
                    return;
                }
                if (message != null || pageWidth == null || pageWidth.length == 0) {
                    RawNotify.warn(message != null ? message : "В PDF нет страниц");
                    finishFragment();
                    RawPdfViewer.openExternal(file, title, getParentActivity());
                    return;
                }
                progress.setVisibility(View.GONE);
                scroller.setVisibility(View.VISIBLE);
                listView.setAdapter(adapter = new PagesAdapter());
                applyZoom(1f);
                updateIndicator();
            });
        });
    }

    private void applyZoom(float newZoom) {
        zoom = Math.max(1f, Math.min(MAX_ZOOM, newZoom));
        ViewGroup.LayoutParams lp = listView.getLayoutParams();
        lp.width = Math.round(viewWidth * zoom);
        listView.setLayoutParams(lp);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void updateIndicator() {
        if (adapter == null || indicator == null) {
            return;
        }
        int first = layoutManager.findFirstVisibleItemPosition();
        if (first < 0) {
            return;
        }
        indicator.setText((first + 1) + " / " + pageWidth.length);
        indicator.animate().cancel();
        indicator.setAlpha(1f);
        AndroidUtilities.cancelRunOnUIThread(hideIndicator);
        AndroidUtilities.runOnUIThread(hideIndicator, 1500);
    }

    private long keyFor(int page, int targetWidth) {
        return ((long) targetWidth << 32) | page;
    }

    private int targetWidth(int page) {
        int w = Math.max(1, Math.round(viewWidth * zoom));
        long h = Math.round((double) w * pageHeight[page] / pageWidth[page]);
        if ((long) w * h > MAX_PIXELS) {
            w = (int) Math.sqrt((double) MAX_PIXELS * pageWidth[page] / pageHeight[page]);
        }
        return Math.max(1, w);
    }

    private void request(int page, int tw) {
        long key = keyFor(page, tw);
        if (pending.put(key, Boolean.TRUE) != null) {
            return;
        }
        queue.postRunnable(() -> {
            Bitmap bitmap = null;
            try {
                if (!closed && wanted.containsKey(page)) {
                    int th = Math.max(1, Math.round((float) tw * pageHeight[page] / pageWidth[page]));
                    bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(0xFFFFFFFF);
                    synchronized (rendererLock) {
                        if (renderer != null) {
                            PdfRenderer.Page p = renderer.openPage(page);
                            try {
                                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            } finally {
                                p.close();
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                bitmap = null;
            }
            Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                pending.remove(key);
                if (result == null || closed) {
                    return;
                }
                cache.put(key, result);
                for (int i = 0; i < listView.getChildCount(); i++) {
                    View child = listView.getChildAt(i);
                    if (child instanceof PageView && ((PageView) child).page == page) {
                        ((PageView) child).bind(page);
                    }
                }
            });
        });
    }

    @Override
    public void onFragmentDestroy() {
        closed = true;
        AndroidUtilities.cancelRunOnUIThread(hideIndicator);
        if (listView != null) {
            listView.setAdapter(null);
        }
        for (Bitmap bitmap : cache.snapshot().values()) {
            bitmap.recycle();
        }
        cache.evictAll();
        wanted.clear();
        DispatchQueue q = queue;
        if (q != null) {
            q.postRunnable(() -> {
                synchronized (rendererLock) {
                    try {
                        if (renderer != null) {
                            renderer.close();
                            renderer = null;
                        }
                        if (descriptor != null) {
                            descriptor.close();
                            descriptor = null;
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
                q.recycle();
            });
        }
        super.onFragmentDestroy();
    }

    private class PageView extends View {
        int page = -1;
        private Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final Rect dst = new Rect();
        private final int gap = AndroidUtilities.dp(6);

        PageView(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(int page) {
            this.page = page;
            bitmap = cache.get(keyFor(page, targetWidth(page)));
            if (bitmap == null) {
                wanted.put(page, Boolean.TRUE);
                request(page, targetWidth(page));
            }
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = page < 0 ? 0 : Math.round((float) w * pageHeight[page] / pageWidth[page]) + gap;
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int h = getHeight() - gap;
            if (bitmap != null && !bitmap.isRecycled()) {
                src.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                dst.set(0, 0, getWidth(), h);
                canvas.drawBitmap(bitmap, src, dst, paint);
            } else {
                paint.setColor(0xFFFFFFFF);
                canvas.drawRect(0, 0, getWidth(), h, paint);
            }
        }
    }

    private class PagesAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new PageView(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((PageView) holder.itemView).bind(position);
        }

        @Override
        public void onViewRecycled(RecyclerView.ViewHolder holder) {
            PageView view = (PageView) holder.itemView;
            if (view.page >= 0) {
                wanted.remove(view.page);
            }
            view.page = -1;
            view.bitmap = null;
        }

        @Override
        public int getItemCount() {
            return pageWidth == null ? 0 : pageWidth.length;
        }
    }

    /** Pinch and double-tap zoom on top of the list. */
    private class ScaleFrame extends FrameLayout {
        private float factor = 1f;
        private float focusX;
        private float focusY;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;

        ScaleFrame(Context context) {
            super(context);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector d) {
                    factor = 1f;
                    return adapter != null;
                }

                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    focusX = d.getFocusX();
                    focusY = d.getFocusY();
                    factor = Math.max(1f / zoom, Math.min(MAX_ZOOM / zoom, factor * d.getScaleFactor()));
                    listView.setPivotX(scroller.getScrollX() + focusX);
                    listView.setPivotY(focusY);
                    listView.setScaleX(factor);
                    listView.setScaleY(factor);
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector d) {
                    listView.setScaleX(1f);
                    listView.setScaleY(1f);
                    zoomAround(zoom * factor, focusX, focusY);
                }
            });
            tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (adapter != null) {
                        zoomAround(zoom > 1.01f ? 1f : MAX_ZOOM, e.getX(), e.getY());
                    }
                    return true;
                }
            });
        }

        private void zoomAround(float target, float fx, float fy) {
            float newZoom = Math.max(1f, Math.min(MAX_ZOOM, target));
            if (Math.abs(newZoom - zoom) < 0.01f) {
                return;
            }
            float ratio = newZoom / zoom;
            int first = layoutManager.findFirstVisibleItemPosition();
            View firstView = layoutManager.findViewByPosition(first);
            int top = firstView == null ? 0 : firstView.getTop();
            float contentX = (scroller.getScrollX() + fx) * ratio - fx;
            applyZoom(newZoom);
            layoutManager.scrollToPositionWithOffset(first, Math.round(top * ratio));
            scroller.post(() -> scroller.scrollTo(Math.max(0, Math.round(contentX)), 0));
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            boolean was = scaleDetector.isInProgress();
            scaleDetector.onTouchEvent(ev);
            tapDetector.onTouchEvent(ev);
            if (scaleDetector.isInProgress()) {
                if (!was) {
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }
    }
}
