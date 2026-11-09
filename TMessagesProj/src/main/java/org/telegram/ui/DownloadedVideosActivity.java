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
import org.telegram.tgnet.TLRPC;
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
import java.util.HashMap;
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
            HashMap<String, CacheModel.FileInfo> videoGroups = new HashMap<>();
            collectManualDownloads(manualDownloads, model, paths, videoGroups);
            // Turrit 的下载记录是主数据源；这里额外接入旧版本已经落盘的视频，
            // 避免升级后原有“已下载视频”突然变成空列表。
            collectVideos(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_VIDEO), model, paths, videoGroups);
            collectVideos(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_VIDEO_PUBLIC), model, paths, videoGroups);
            finalizeVideoGroups(videoGroups);
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

    private void collectManualDownloads(ArrayList<MessageObject> messages, CacheModel model, HashSet<String> paths, HashMap<String, CacheModel.FileInfo> videoGroups) {
        for (MessageObject messageObject : messages) {
            if (!isVideoDownload(messageObject)) {
                continue;
            }
            // 只遍历下载记录对应的消息，不再扫描整个 Telegram Video 目录；
            // 这样播放器缓存不会被误当成“已下载视频”。
            addDocumentVersion(messageObject, messageObject.getDocument(), model, paths, videoGroups);
            TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
            if (media instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.TL_messageMediaDocument mediaDocument = (TLRPC.TL_messageMediaDocument) media;
                if (mediaDocument.alt_documents != null) {
                    for (int i = 0; i < mediaDocument.alt_documents.size(); i++) {
                        addDocumentVersion(messageObject, mediaDocument.alt_documents.get(i), model, paths, videoGroups);
                    }
                }
            }
        }
    }

    private void addDocumentVersion(MessageObject messageObject, TLRPC.Document document, CacheModel model, HashSet<String> paths, HashMap<String, CacheModel.FileInfo> videoGroups) {
        if (document == null) {
            return;
        }
        File file = getFileLoader().getPathToAttach(document);
        if (!isCompleteFile(file, document.size)) {
            return;
        }
        addVideo(file, messageObject, document, model, paths, videoGroups);
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

    private void collectVideos(File directory, CacheModel model, HashSet<String> paths, HashMap<String, CacheModel.FileInfo> videoGroups) {
        if (directory == null) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectVideos(file, model, paths, videoGroups);
            } else if (!file.getName().equals(".nomedia") && isCompleteFile(file, 0)) {
                addVideo(file, null, null, model, paths, videoGroups);
            }
        }
    }

    private void addVideo(File file, MessageObject messageObject, TLRPC.Document document, CacheModel model, HashSet<String> paths, HashMap<String, CacheModel.FileInfo> videoGroups) {
        String path = file.getAbsolutePath();
        if (!paths.add(path)) {
            return;
        }
        CacheModel.FileInfo fileInfo = new CacheModel.FileInfo(file);
        fileInfo.size = file.length();
        fileInfo.type = CacheControlActivity.TYPE_VIDEOS;
        fileInfo.messageObject = messageObject;
        fileInfo.documentId = document != null ? document.id : 0;
        fileInfo.qualityLabel = getQualityLabel(document);
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

        String groupKey = getVideoGroupKey(fileInfo);
        if (groupKey != null) {
            CacheModel.FileInfo existing = videoGroups.get(groupKey);
            if (existing != null) {
                if (existing.versions == null) {
                    existing.versions = new ArrayList<>();
                    existing.versions.add(existing);
                }
                existing.versions.add(fileInfo);
                existing.qualityCount = existing.versions.size();
                if (fileInfo.size > existing.size) {
                    model.media.remove(existing);
                    fileInfo.versions = existing.versions;
                    fileInfo.qualityCount = existing.qualityCount;
                    model.media.add(fileInfo);
                    videoGroups.put(groupKey, fileInfo);
                }
                return;
            }
            videoGroups.put(groupKey, fileInfo);
        }
        model.media.add(fileInfo);
    }

    private String getQualityLabel(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int i = 0; i < document.attributes.size(); i++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(i);
            if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                TLRPC.TL_documentAttributeVideo video = (TLRPC.TL_documentAttributeVideo) attribute;
                if (video.w > 0 && video.h > 0) {
                    return video.w + "×" + video.h;
                }
            }
        }
        return null;
    }

    private String getVideoGroupKey(CacheModel.FileInfo fileInfo) {
        if (fileInfo.dialogId != 0 && fileInfo.messageId != 0) {
            return fileInfo.dialogId + ":" + fileInfo.messageId;
        }
        return null;
    }

    private void finalizeVideoGroups(HashMap<String, CacheModel.FileInfo> videoGroups) {
        for (CacheModel.FileInfo fileInfo : videoGroups.values()) {
            if (fileInfo.versions == null || fileInfo.versions.isEmpty()) {
                continue;
            }
            fileInfo.versions.sort((a, b) -> Long.compare(b.size, a.size));
            fileInfo.qualityCount = fileInfo.versions.size();
        }
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
        ArrayList<CacheModel.FileInfo> filesToDelete = new ArrayList<>();
        HashSet<String> selectedPaths = new HashSet<>();
        for (CacheModel.FileInfo fileInfo : selected) {
            if (fileInfo.versions != null && !fileInfo.versions.isEmpty()) {
                for (CacheModel.FileInfo version : fileInfo.versions) {
                    if (selectedPaths.add(version.file.getAbsolutePath())) {
                        filesToDelete.add(version);
                    }
                }
            } else if (selectedPaths.add(fileInfo.file.getAbsolutePath())) {
                filesToDelete.add(fileInfo);
            }
        }

        ArrayList<MessageObject> trackedMessages = new ArrayList<>();
        HashSet<String> selectedGroupKeys = new HashSet<>();
        for (CacheModel.FileInfo fileInfo : selected) {
            String groupKey = getVideoGroupKey(fileInfo);
            if (groupKey != null) {
                selectedGroupKeys.add(groupKey);
            }
        }
        for (MessageObject messageObject : DownloadController.getInstance(currentAccount).recentDownloadingFiles) {
            File file = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
            String groupKey = messageObject.getDialogId() + ":" + messageObject.getId();
            if (file != null && selectedPaths.contains(file.getAbsolutePath()) || selectedGroupKeys.contains(groupKey)) {
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
            for (CacheModel.FileInfo fileInfo : filesToDelete) {
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
