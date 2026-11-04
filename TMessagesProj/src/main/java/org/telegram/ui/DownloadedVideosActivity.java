package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FilePathDatabase;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.StickerEmptyView;
import org.telegram.ui.Storage.CacheModel;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

public class DownloadedVideosActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int DELETE = 1;
    private static final int ZOOM_IN = 2;
    private static final int ZOOM_OUT = 3;
    private static final int MIN_COLUMNS_COUNT = 2;
    private static final int MAX_COLUMNS_COUNT = 6;
    private static final int DEFAULT_COLUMNS_COUNT = 3;
    private static final String COLUMNS_COUNT_PREFERENCE = "downloaded_videos_columns_count";

    private CachedMediaLayout mediaLayout;
    private CacheModel cacheModel;
    private StickerEmptyView emptyView;
    private NumberTextView selectedCountView;
    private ActionBarMenuItem zoomInItem;
    private ActionBarMenuItem zoomOutItem;
    private boolean loading;
    private boolean reloadPending;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.onDownloadingFilesChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.onDownloadingFilesChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (fragmentView != null) {
            loadVideos();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle("已下载视频");
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (cacheModel != null && cacheModel.getSelectedFiles() > 0) {
                        clearSelection();
                    } else {
                        finishFragment();
                    }
                } else if (id == DELETE) {
                    confirmDeleteSelected();
                } else if (id == ZOOM_IN) {
                    mediaLayout.zoomMediaIn();
                } else if (id == ZOOM_OUT) {
                    mediaLayout.zoomMediaOut();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        zoomInItem = menu.addItem(ZOOM_IN, R.drawable.msg_zoomin);
        zoomInItem.setContentDescription(LocaleController.getString(R.string.MediaZoomIn));
        zoomOutItem = menu.addItem(ZOOM_OUT, R.drawable.msg_zoomout);
        zoomOutItem.setContentDescription(LocaleController.getString(R.string.MediaZoomOut));

        ActionBarMenu actionMode = actionBar.createActionMode();
        selectedCountView = new NumberTextView(context);
        selectedCountView.setTextSize(18);
        selectedCountView.setTypeface(AndroidUtilities.bold());
        selectedCountView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        selectedCountView.setOnTouchListener((v, event) -> true);
        actionMode.addView(selectedCountView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        actionMode.addItem(DELETE, R.drawable.msg_delete).setContentDescription("删除本地文件");

        FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        mediaLayout = new CachedMediaLayout(context, this) {
            @Override
            protected void showActionMode(boolean show) {
                if (show) {
                    actionBar.showActionMode();
                } else {
                    actionBar.hideActionMode();
                }
            }

            @Override
            protected boolean actionModeIsVisible() {
                return actionBar.isActionModeShowed();
            }
        };
        mediaLayout.setMediaZoomEnabled(true);
        mediaLayout.setMediaColumnsCountRange(MIN_COLUMNS_COUNT, MAX_COLUMNS_COUNT);
        mediaLayout.setMediaColumnsCount(MessagesController.getGlobalMainSettings().getInt(COLUMNS_COUNT_PREFERENCE, DEFAULT_COLUMNS_COUNT));
        mediaLayout.setMediaColumnsCountListener(columnsCount -> {
            MessagesController.getGlobalMainSettings().edit().putInt(COLUMNS_COUNT_PREFERENCE, columnsCount).apply();
            updateZoomItems();
        });
        updateZoomItems();
        mediaLayout.setDelegate(new CachedMediaLayout.Delegate() {
            @Override
            public void onItemSelected(CacheControlActivity.DialogFileEntities entities, CacheModel.FileInfo fileInfo, boolean longPress) {
                if (fileInfo == null || cacheModel == null) {
                    return;
                }
                if (cacheModel.getSelectedFiles() > 0 || longPress) {
                    cacheModel.toggleSelect(fileInfo);
                    updateSelection();
                    mediaLayout.updateVisibleRows();
                }
            }

            @Override
            public void clear() {
                confirmDeleteSelected();
            }

            @Override
            public void clearSelection() {
                DownloadedVideosActivity.this.clearSelection();
            }
        });
        content.addView(mediaLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        FlickerLoadingView loadingView = new FlickerLoadingView(context);
        loadingView.setViewType(FlickerLoadingView.PHOTOS_TYPE);
        emptyView = new StickerEmptyView(context, loadingView, StickerEmptyView.STICKER_TYPE_SEARCH);
        emptyView.title.setText("暂无已下载视频");
        content.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = content;
        loadVideos();
        return fragmentView;
    }

    private void loadVideos() {
        if (loading) {
            reloadPending = true;
            return;
        }
        loading = true;
        if (emptyView != null) {
            emptyView.showProgress(true, false);
        }
        ArrayList<MessageObject> manualDownloads = new ArrayList<>(DownloadController.getInstance(currentAccount).recentDownloadingFiles);
        getFileLoader().getFileDatabase().getQueue().postRunnable(() -> {
            CacheModel model = new CacheModel(false);
            HashSet<String> paths = new HashSet<>();
            collectManualDownloads(manualDownloads, model, paths);
            collectVideos(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_VIDEO), model, paths);
            collectVideos(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_VIDEO_PUBLIC), model, paths);
            model.media.sort((a, b) -> Long.compare(b.file.lastModified(), a.file.lastModified()));
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                cacheModel = model;
                if (mediaLayout != null) {
                    mediaLayout.setCacheModel(model);
                    mediaLayout.setVisibility(model.media.isEmpty() ? View.GONE : View.VISIBLE);
                }
                if (emptyView != null) {
                    emptyView.showProgress(false, true);
                    emptyView.setVisibility(model.media.isEmpty() ? View.VISIBLE : View.GONE);
                }
                if (reloadPending) {
                    reloadPending = false;
                    loadVideos();
                }
            });
        });
    }

    private void collectManualDownloads(ArrayList<MessageObject> messages, CacheModel model, HashSet<String> paths) {
        for (MessageObject messageObject : messages) {
            if (!isVideoDownload(messageObject)) {
                continue;
            }
            File file = getFileLoader().getPathToMessage(messageObject.messageOwner);
            if (!isCompleteFile(file, messageObject.getDocument().size)) {
                continue;
            }
            addVideo(file, messageObject, model, paths);
        }
    }

    private boolean isVideoDownload(MessageObject messageObject) {
        if (messageObject == null || messageObject.getDocument() == null) {
            return false;
        }
        String mimeType = messageObject.getDocument().mime_type;
        return messageObject.isVideo() || MessageObject.isVideoDocument(messageObject.getDocument()) || mimeType != null && mimeType.startsWith("video/");
    }

    private boolean isCompleteFile(File file, long expectedSize) {
        if (file == null || !file.isFile() || file.length() <= 0) {
            return false;
        }
        String name = file.getName().toLowerCase();
        if (name.endsWith(".temp") || name.endsWith(".preload") || name.endsWith(".enc")) {
            return false;
        }
        return expectedSize <= 0 || file.length() >= expectedSize;
    }

    private void collectVideos(File directory, CacheModel model, HashSet<String> paths) {
        if (directory == null) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectVideos(file, model, paths);
            } else if (!file.getName().equals(".nomedia") && isCompleteFile(file, 0)) {
                addVideo(file, null, model, paths);
            }
        }
    }

    private void addVideo(File file, MessageObject messageObject, CacheModel model, HashSet<String> paths) {
        String path = file.getAbsolutePath();
        if (!paths.add(path)) {
            return;
        }
        CacheModel.FileInfo fileInfo = new CacheModel.FileInfo(file);
        fileInfo.size = file.length();
        fileInfo.type = CacheControlActivity.TYPE_VIDEOS;
        fileInfo.messageObject = messageObject;
        if (messageObject != null) {
            fileInfo.dialogId = messageObject.getDialogId();
            fileInfo.messageId = messageObject.getId();
            fileInfo.messageType = messageObject.type;
        } else {
            FilePathDatabase.FileMeta metadata = getFileLoader().getFileDatabase().getFileDialogId(file, null);
            if (metadata != null) {
                fileInfo.dialogId = metadata.dialogId;
                fileInfo.messageId = metadata.messageId;
                fileInfo.messageType = metadata.messageType;
            }
        }
        model.media.add(fileInfo);
    }

    private void updateSelection() {
        int count = cacheModel == null ? 0 : cacheModel.getSelectedFiles();
        if (count == 0) {
            actionBar.hideActionMode();
        } else {
            actionBar.showActionMode();
            selectedCountView.setNumber(count, true);
        }
    }

    private void updateZoomItems() {
        if (mediaLayout == null || zoomInItem == null || zoomOutItem == null) {
            return;
        }
        zoomInItem.setEnabled(mediaLayout.canZoomMediaIn());
        zoomInItem.setAlpha(zoomInItem.isEnabled() ? 1f : 0.5f);
        zoomOutItem.setEnabled(mediaLayout.canZoomMediaOut());
        zoomOutItem.setAlpha(zoomOutItem.isEnabled() ? 1f : 0.5f);
    }

    private void clearSelection() {
        if (cacheModel != null) {
            cacheModel.clearSelection();
        }
        actionBar.hideActionMode();
        if (mediaLayout != null) {
            mediaLayout.updateVisibleRows();
        }
    }

    private void confirmDeleteSelected() {
        if (cacheModel == null || cacheModel.getSelectedFiles() == 0) {
            return;
        }
        int count = cacheModel.getSelectedFiles();
        showDialog(new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle("删除本地视频")
                .setMessage("将从设备删除选中的 " + count + " 个视频，不会删除聊天消息。")
                .setPositiveButton("删除", (dialog, which) -> deleteSelected())
                .setNegativeButton("取消", null)
                .create());
    }

    private void deleteSelected() {
        ArrayList<CacheModel.FileInfo> selected = new ArrayList<>(cacheModel.selectedFiles);
        HashSet<String> selectedPaths = new HashSet<>();
        for (CacheModel.FileInfo fileInfo : selected) {
            selectedPaths.add(fileInfo.file.getAbsolutePath());
        }

        ArrayList<MessageObject> trackedMessages = new ArrayList<>();
        for (MessageObject messageObject : DownloadController.getInstance(currentAccount).recentDownloadingFiles) {
            File file = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
            if (file != null && selectedPaths.contains(file.getAbsolutePath())) {
                trackedMessages.add(messageObject);
            }
        }
        if (!trackedMessages.isEmpty()) {
            DownloadController.getInstance(currentAccount).deleteRecentFiles(trackedMessages);
        }

        cacheModel.removeSelectedFiles();
        cacheModel.clearSelection();
        actionBar.hideActionMode();
        Utilities.globalQueue.postRunnable(() -> {
            for (CacheModel.FileInfo fileInfo : selected) {
                try {
                    fileInfo.file.delete();
                } catch (Exception ignore) {
                }
            }
            AndroidUtilities.runOnUIThread(this::loadVideos);
        });
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.onDownloadingFilesChanged && fragmentView != null) {
            loadVideos();
        }
    }
}
