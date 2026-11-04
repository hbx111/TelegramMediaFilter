package org.telegram.ui.Components;

import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

import androidx.recyclerview.widget.RecyclerView;

public class RecyclerViewItemRangeSelector implements RecyclerView.OnItemTouchListener {

    private RecyclerView recyclerView;

    private int lastDraggedIndex = -1;
    private int initialSelection;
    private int previousRangeStart = -1;
    private int previousRangeEnd = -1;
    private boolean targetSelection;
    private final SparseBooleanArray originalSelectionStates = new SparseBooleanArray();
    private boolean dragSelectActive;

    private int hotspotTopBoundStart;
    private int hotspotTopBoundEnd;
    private int hotspotBottomBoundStart;
    private int hotspotBottomBoundEnd;
    private boolean inTopHotspot;
    private boolean inBottomHotspot;

    private int autoScrollVelocity;
    private boolean isAutoScrolling;
    private float lastTouchX;
    private float lastTouchY;

    private int hotspotHeight = AndroidUtilities.dp(80);
    private int hotspotOffsetTop;
    private int hotspotOffsetBottom;

    private RecyclerViewItemRangeSelectorDelegate delegate;

    private static final int AUTO_SCROLL_DELAY = 15;

    public interface RecyclerViewItemRangeSelectorDelegate {
        int getItemCount();
        void setSelected(View view, int index, boolean selected);
        boolean isSelected(int index);
        boolean isIndexSelectable(int index);
        void onStartStopSelection(boolean start);
    }

    private Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (recyclerView == null) {
                return;
            }
            if (inTopHotspot) {
                recyclerView.scrollBy(0, -autoScrollVelocity);
                selectItemUnder(lastTouchX, lastTouchY);
                AndroidUtilities.runOnUIThread(this, AUTO_SCROLL_DELAY);
            } else if (inBottomHotspot) {
                recyclerView.scrollBy(0, autoScrollVelocity);
                selectItemUnder(lastTouchX, lastTouchY);
                AndroidUtilities.runOnUIThread(this, AUTO_SCROLL_DELAY);
            }
        }
    };

    public RecyclerViewItemRangeSelector(RecyclerViewItemRangeSelectorDelegate recyclerViewItemRangeSelectorDelegate) {
        delegate = recyclerViewItemRangeSelectorDelegate;
    }

    private void disableAutoScroll() {
        hotspotHeight = -1;
        hotspotOffsetTop = -1;
        hotspotOffsetBottom = -1;
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        boolean adapterIsEmpty = rv.getAdapter() == null || rv.getAdapter().getItemCount() == 0;
        boolean result = dragSelectActive && !adapterIsEmpty;

        if (result) {
            recyclerView = rv;

            if (hotspotHeight > -1) {
                hotspotTopBoundStart = hotspotOffsetTop;
                hotspotTopBoundEnd = hotspotOffsetTop + hotspotHeight;
                hotspotBottomBoundStart = rv.getMeasuredHeight() - hotspotHeight - hotspotOffsetBottom;
                hotspotBottomBoundEnd = rv.getMeasuredHeight() - hotspotOffsetBottom;
            }
        }

        if (result && (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)) {
            onDragSelectionStop();
        }
        return result;
    }

    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        lastTouchX = e.getX();
        lastTouchY = e.getY();
        float y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_UP: {
                onDragSelectionStop();
                return;
            }
            case MotionEvent.ACTION_CANCEL: {
                onDragSelectionStop();
                return;
            }
            case MotionEvent.ACTION_MOVE: {
                if (hotspotHeight > -1) {
                    if (y >= hotspotTopBoundStart && y <= hotspotTopBoundEnd) {
                        inBottomHotspot = false;
                        if (!inTopHotspot) {
                            inTopHotspot = true;
                            AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
                            AndroidUtilities.runOnUIThread(autoScrollRunnable, AUTO_SCROLL_DELAY);
                        }
                        float simulatedFactor = (hotspotTopBoundEnd - hotspotTopBoundStart);
                        float simulatedY = y - hotspotTopBoundStart;
                        autoScrollVelocity = (int) (simulatedFactor - simulatedY) / 2;
                    } else if (y >= hotspotBottomBoundStart && y <= hotspotBottomBoundEnd) {
                        inTopHotspot = false;
                        if (!inBottomHotspot) {
                            inBottomHotspot = true;
                            AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
                            AndroidUtilities.runOnUIThread(autoScrollRunnable, AUTO_SCROLL_DELAY);
                        }
                        float simulatedY = y + hotspotBottomBoundEnd;
                        float simulatedFactor = (hotspotBottomBoundStart + hotspotBottomBoundEnd);
                        autoScrollVelocity = (int) (simulatedY - simulatedFactor) / 2;
                    } else if (inTopHotspot || inBottomHotspot) {
                        AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
                        inTopHotspot = false;
                        inBottomHotspot = false;
                    }
                }

                selectItemUnder(lastTouchX, lastTouchY);
                break;
            }
        }
    }

    private void selectItemUnder(float x, float y) {
        if (recyclerView == null) {
            return;
        }
        View view = recyclerView.findChildViewUnder(x, y);
        if (view == null) {
            return;
        }
        int itemPosition = recyclerView.getChildAdapterPosition(view);
        if (itemPosition == RecyclerView.NO_POSITION || lastDraggedIndex == itemPosition) {
            return;
        }
        lastDraggedIndex = itemPosition;
        updateRange(itemPosition);
    }

    private void updateRange(int endPosition) {
        int newStart = Math.min(initialSelection, endPosition);
        int newEnd = Math.max(initialSelection, endPosition);

        if (previousRangeStart < 0 || previousRangeEnd < 0) {
            applyRange(newStart, newEnd, true);
        } else {
            if (newStart < previousRangeStart) {
                applyRange(newStart, previousRangeStart - 1, true);
            } else if (newStart > previousRangeStart) {
                applyRange(previousRangeStart, newStart - 1, false);
            }
            if (newEnd > previousRangeEnd) {
                applyRange(previousRangeEnd + 1, newEnd, true);
            } else if (newEnd < previousRangeEnd) {
                applyRange(newEnd + 1, previousRangeEnd, false);
            }
        }
        previousRangeStart = newStart;
        previousRangeEnd = newEnd;
    }

    private void applyRange(int start, int end, boolean inCurrentRange) {
        if (start > end) {
            return;
        }
        for (int index = start; index <= end; index++) {
            if (!delegate.isIndexSelectable(index)) {
                continue;
            }
            if (originalSelectionStates.indexOfKey(index) < 0) {
                originalSelectionStates.put(index, delegate.isSelected(index));
            }
            boolean selected = inCurrentRange ? targetSelection : originalSelectionStates.get(index);
            if (delegate.isSelected(index) == selected) {
                continue;
            }
            View itemView = null;
            if (recyclerView != null) {
                RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(index);
                if (holder != null) {
                    itemView = holder.itemView;
                }
            }
            delegate.setSelected(itemView, index, selected);
        }
    }
    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {

    }

    public boolean setIsActive(View view, boolean active, int selection, boolean select) {
        if (active && dragSelectActive) {
            return false;
        }

        lastDraggedIndex = -1;
        previousRangeStart = -1;
        previousRangeEnd = -1;
        originalSelectionStates.clear();
        AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
        inTopHotspot = false;
        inBottomHotspot = false;

        if (!active) {
            initialSelection = -1;
            return false;
        }

        if (!delegate.isIndexSelectable(selection)) {
            dragSelectActive = false;
            initialSelection = -1;
            return false;
        }

        initialSelection = selection;
        targetSelection = select;
        originalSelectionStates.put(selection, delegate.isSelected(selection));
        delegate.onStartStopSelection(true);
        if (delegate.isSelected(selection) != select) {
            delegate.setSelected(view, selection, select);
        }
        dragSelectActive = active;
        lastDraggedIndex = selection;
        previousRangeStart = selection;
        previousRangeEnd = selection;

        return true;
    }

    public boolean isActive() {
        return dragSelectActive;
    }

    public void stopSelection() {
        if (dragSelectActive) {
            onDragSelectionStop();
        }
    }

    private void onDragSelectionStop() {
        dragSelectActive = false;
        inTopHotspot = false;
        inBottomHotspot = false;
        AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
        delegate.onStartStopSelection(false);
    }
}
