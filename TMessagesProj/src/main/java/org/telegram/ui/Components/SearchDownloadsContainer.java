package org.telegram.ui.Components;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Cells.SharedDocumentCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.DownloadedVideosActivity;
import org.telegram.ui.FilteredSearchView;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.PremiumPreviewFragment;

import java.util.ArrayList;

@SuppressLint("ViewConstructor")
public class SearchDownloadsContainer extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final FlickerLoadingView loadingView;
    StickerEmptyView emptyView;
    public final @NonNull RecyclerListView recyclerListView;
    private ScrollSlidingTextTabStrip downloadTabs;
    DownloadsAdapter adapter = new DownloadsAdapter();
    private final int currentAccount;

    private static final int TAB_DOWNLOADING = 0;
    private static final int TAB_COMPLETED = 1;
    private static final int TAB_FAILED = 2;
    private static final int TABS_HEIGHT_DP = 44;
    private int selectedDownloadTab = TAB_DOWNLOADING;

    ArrayList<MessageObject> currentLoadingFiles = new ArrayList<>();
    ArrayList<MessageObject> recentLoadingFiles = new ArrayList<>();
    ArrayList<MessageObject> failedLoadingFiles = new ArrayList<>();

    ArrayList<MessageObject> currentLoadingFilesTmp = new ArrayList<>();
    ArrayList<MessageObject> recentLoadingFilesTmp = new ArrayList<>();
    ArrayList<MessageObject> failedLoadingFilesTmp = new ArrayList<>();

    int rowCount;
    int downloadedVideosRow = -1;
    int downloadingFilesHeader = -1;
    int downloadingFilesStartRow = -1;
    int downloadingFilesEndRow = -1;
    int recentFilesHeader = -1;
    int recentFilesStartRow = -1;
    int recentFilesEndRow = -1;
    int failedFilesHeader = -1;
    int failedFilesStartRow = -1;
    int failedFilesEndRow = -1;

    Activity parentActivity;
    BaseFragment parentFragment;
    private boolean hasCurrentDownload;

    FilteredSearchView.UiCallback uiCallback;
    private final FilteredSearchView.MessageHashId messageHashIdTmp = new FilteredSearchView.MessageHashId(0, 0);
    String searchQuery;
    String lastQueryString;
    Runnable lastSearchRunnable;
    RecyclerItemsEnterAnimator itemsEnterAnimator;

    boolean checkingFilesExist;

    private final Runnable downloadStatusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) {
                return;
            }
            refreshVisibleDownloadStatuses();
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    public SearchDownloadsContainer(BaseFragment fragment, int currentAccount) {
        super(fragment.getParentActivity());
        this.parentFragment = fragment;
        this.parentActivity = fragment.getParentActivity();
        this.currentAccount = currentAccount;
        recyclerListView = new RecyclerListView(getContext()) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                checkItemsFloodWait();
            }
        };
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(recyclerListView);
        addView(recyclerListView);
        recyclerListView.setLayoutManager(new LinearLayoutManager(fragment.getParentActivity()) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return true;
            }
        });
        recyclerListView.setAdapter(adapter);
        recyclerListView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    AndroidUtilities.hideKeyboard(parentActivity.getCurrentFocus());
                }
                checkItemsFloodWait();
            }
        });
        DefaultItemAnimator defaultItemAnimator = new DefaultItemAnimator();
        defaultItemAnimator.setDelayAnimations(false);
        defaultItemAnimator.setSupportsChangeAnimations(false);
        recyclerListView.setItemAnimator(defaultItemAnimator);

        recyclerListView.setOnItemClickListener((view, position) -> {
            if (position == downloadedVideosRow) {
                parentFragment.presentFragment(new DownloadedVideosActivity());
                return;
            }
            MessageObject messageObject = adapter.getMessage(position);
            if (messageObject == null) {
                return;
            }
            if (uiCallback.actionModeShowing()) {
                uiCallback.toggleItemSelection(messageObject, view, 0);

                messageHashIdTmp.set(messageObject.getId(), messageObject.getDialogId());
                adapter.notifyItemChanged(position);
                if (!uiCallback.actionModeShowing()) {
                    adapter.notifyItemRangeChanged(0, adapter.getItemCount());
                }
                return;
            }

            DownloadController downloadController = DownloadController.getInstance(currentAccount);
            if (selectedDownloadTab == TAB_DOWNLOADING) {
                if (FileLoader.getInstance(currentAccount).isLoadingFile(messageObject.getFileName())) {
                    downloadController.pauseDownload(messageObject);
                } else {
                    downloadController.resumeDownload(messageObject);
                }
                update(true);
                return;
            } else if (selectedDownloadTab == TAB_FAILED) {
                downloadController.retryDownload(messageObject);
                selectedDownloadTab = TAB_DOWNLOADING;
                downloadTabs.setInitialTabId(TAB_DOWNLOADING);
                update(false);
                return;
            }

            if (view instanceof Cell) {
                SharedDocumentCell cell = ((Cell) view).sharedDocumentCell;
                MessageObject message = cell.getMessage();
                TLRPC.Document document = message.getDocument();
                if (cell.isLoaded()) {
                    if (message.isRoundVideo() || message.isVoice()) {
                        MediaController.getInstance().playMessage(message);
                        return;
                    }
                    boolean openInPhotoViewer = message.canPreviewDocument();
                    if (!openInPhotoViewer) {
                        boolean noforwards = message.messageOwner != null && message.messageOwner.noforwards;
                        TLRPC.Chat chatTo = messageObject.messageOwner.peer_id.channel_id != 0 ? MessagesController.getInstance(UserConfig.selectedAccount).getChat(messageObject.messageOwner.peer_id.channel_id) : null;
                        if (chatTo == null) {
                            chatTo = messageObject.messageOwner.peer_id.chat_id != 0 ? MessagesController.getInstance(UserConfig.selectedAccount).getChat(messageObject.messageOwner.peer_id.chat_id) : null;
                        }
                        if (chatTo != null) {
                            noforwards = chatTo.noforwards;
                        }
                        openInPhotoViewer = openInPhotoViewer || noforwards;
                    }
                    if (openInPhotoViewer) {
                        PhotoViewer.getInstance().setParentActivity(parentFragment);

                        ArrayList<MessageObject> documents = new ArrayList<>();
                        documents.add(message);
                        PhotoViewer.getInstance().setParentActivity(parentFragment);
                        PhotoViewer.getInstance().openPhoto(documents, 0, 0, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
                        return;
                    }
                    AndroidUtilities.openDocument(message, parentActivity, parentFragment);
                } else if (!cell.isLoading()) {
                    messageObject.putInDownloadsStore = true;
                    AccountInstance.getInstance(UserConfig.selectedAccount).getFileLoader().loadFile(document, messageObject, FileLoader.PRIORITY_LOW, 0);
                    cell.updateFileExistIcon(true);
                    DownloadController.getInstance(currentAccount).updateFilesLoadingPriority();
                } else {
                    AccountInstance.getInstance(UserConfig.selectedAccount).getFileLoader().cancelLoadFile(document);
                    cell.updateFileExistIcon(true);
                }
                update(true);
            }
            if (view instanceof SharedAudioCell) {
                SharedAudioCell cell = (SharedAudioCell) view;
                cell.didPressedButton();
            }
        });
        recyclerListView.setOnItemLongClickListener((view, position) -> {
            MessageObject messageObject = adapter.getMessage(position);
            if (messageObject != null) {
                if (!uiCallback.actionModeShowing()) {
                    uiCallback.showActionMode();
                    adapter.notifyItemRangeChanged(0, adapter.getItemCount());
                }
                if (uiCallback.actionModeShowing()) {
                    uiCallback.toggleItemSelection(messageObject, view, 0);
                    if (!uiCallback.actionModeShowing()) {
                        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
                    }
                    messageHashIdTmp.set(messageObject.getId(), messageObject.getDialogId());
                }
                return true;
            }
            return false;
        });
        itemsEnterAnimator = new RecyclerItemsEnterAnimator(recyclerListView, true);


        addView(loadingView = new FlickerLoadingView(getContext()));
        loadingView.setUseHeaderOffset(true);
        loadingView.setViewType(FlickerLoadingView.FILES_TYPE);
        loadingView.setVisibility(View.GONE);
        emptyView = new StickerEmptyView(getContext(), loadingView, StickerEmptyView.STICKER_TYPE_SEARCH);
        addView(emptyView);
        recyclerListView.setEmptyView(emptyView);

        downloadTabs = new ScrollSlidingTextTabStrip(getContext());
        downloadTabs.setUseSameWidth(true);
        downloadTabs.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        downloadTabs.setColors(Theme.key_profile_tabSelectedLine, Theme.key_profile_tabSelectedText, Theme.key_profile_tabText, Theme.key_profile_tabSelector);
        downloadTabs.addTextTab(TAB_DOWNLOADING, LocaleController.getString(R.string.Downloading));
        downloadTabs.addTextTab(TAB_COMPLETED, "\u5df2\u5b8c\u6210");
        downloadTabs.addTextTab(TAB_FAILED, "\u5931\u8d25");
        downloadTabs.setInitialTabId(selectedDownloadTab);
        downloadTabs.finishAddingTabs();
        downloadTabs.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
            @Override
            public void onPageSelected(int id, boolean forward) {
                if (selectedDownloadTab == id) {
                    return;
                }
                selectedDownloadTab = id;
                recyclerListView.scrollToPosition(0);
                update(false);
            }

            @Override
            public void onPageScrolled(float progress) {
            }
        });
        addView(downloadTabs, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, TABS_HEIGHT_DP, Gravity.TOP));

        FileLoader.getInstance(currentAccount).getCurrentLoadingFiles(currentLoadingFiles);
    }

    public void setPagesPaddings(int top, int bottom) {
        setPagesPaddings(top, bottom, false);
    }

    public void setPagesPaddings(int top, int bottom, boolean doNotRequestLayout) {
        setClipToPadding(false);
        ignoreRequestLayout = doNotRequestLayout;

        setPadding(0, top, 0, bottom);
        recyclerListView.setPadding(0, top + AndroidUtilities.dp(TABS_HEIGHT_DP), 0, bottom, doNotRequestLayout);

        MarginLayoutParams lp = null;
        lp = (MarginLayoutParams) recyclerListView.getLayoutParams();
        lp.topMargin = -top;
        lp.bottomMargin = -bottom;

        ignoreRequestLayout = false;
    }

    private boolean ignoreRequestLayout;

    @Override
    public void requestLayout() {
        if (ignoreRequestLayout) {
            return;
        }
        super.requestLayout();
    }

    private void checkFilesExist() {
        if (checkingFilesExist) {
            return;
        }
        checkingFilesExist = true;
        Utilities.searchQueue.postRunnable(() -> {
            ArrayList<MessageObject> currentLoadingFiles = new ArrayList<>();
            ArrayList<MessageObject> recentLoadingFiles = new ArrayList<>();

            ArrayList<MessageObject> moveToRecent = new ArrayList<>();
            ArrayList<MessageObject> removeFromRecent = new ArrayList<>();

            FileLoader.getInstance(currentAccount).getCurrentLoadingFiles(currentLoadingFiles);
            FileLoader.getInstance(currentAccount).getRecentLoadingFiles(recentLoadingFiles);

            for (int i = 0; i < currentLoadingFiles.size(); i++) {
                if (FileLoader.getInstance(currentAccount).getPathToMessage(currentLoadingFiles.get(i).messageOwner).exists()) {
                    moveToRecent.add(currentLoadingFiles.get(i));
                }
            }

            for (int i = 0; i < recentLoadingFiles.size(); i++) {
                if (!FileLoader.getInstance(currentAccount).getPathToMessage(recentLoadingFiles.get(i).messageOwner).exists()) {
                    removeFromRecent.add(recentLoadingFiles.get(i));
                }
            }

            AndroidUtilities.runOnUIThread(() -> {
                for (int i = 0; i < moveToRecent.size(); i++) {
                    DownloadController.getInstance(currentAccount).onDownloadComplete(moveToRecent.get(i));
                }
                if (!removeFromRecent.isEmpty()) {
                    DownloadController.getInstance(currentAccount).deleteRecentFiles(removeFromRecent);
                }
                checkingFilesExist = false;
                update(true);
            });
        });
    }

    public void update(boolean animated) {
        ArrayList<MessageObject> current = new ArrayList<>();
        ArrayList<MessageObject> recent = new ArrayList<>();
        ArrayList<MessageObject> failed = new ArrayList<>();
        FileLoader.getInstance(currentAccount).getCurrentLoadingFiles(current);
        FileLoader.getInstance(currentAccount).getRecentLoadingFiles(recent);
        DownloadController.getInstance(currentAccount).getFailedLoadingFiles(failed);

        for (int i = 0; i < current.size(); i++) {
            current.get(i).setQuery(null);
        }
        for (int i = 0; i < recent.size(); i++) {
            recent.get(i).setQuery(null);
        }
        for (int i = 0; i < failed.size(); i++) {
            failed.get(i).setQuery(null);
        }

        if (!TextUtils.isEmpty(searchQuery)) {
            String query = searchQuery.toLowerCase();
            current = filterTasks(current, query);
            recent = filterTasks(recent, query);
            failed = filterTasks(failed, query);
        }

        int oldRowCount = rowCount;
        updateListInternal(animated, current, recent, failed);
        if (oldRowCount == 0 && rowCount > 0) {
            itemsEnterAnimator.showItemsAnimated(0);
        }
        emptyView.showProgress(false, animated);
        if (rowCount == 0) {
            if (TextUtils.isEmpty(searchQuery)) {
                emptyView.title.setText(LocaleController.getString(R.string.SearchEmptyViewDownloads));
                emptyView.subtitle.setVisibility(View.GONE);
                emptyView.setStickerType(9);
            } else {
                emptyView.title.setText(LocaleController.getString(R.string.SearchEmptyViewTitle2));
                emptyView.subtitle.setVisibility(View.VISIBLE);
                emptyView.subtitle.setText(LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitle2));
                emptyView.setStickerType(1);
            }
        }
    }

    private ArrayList<MessageObject> filterTasks(ArrayList<MessageObject> source, String query) {
        ArrayList<MessageObject> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            MessageObject sourceMessage = source.get(i);
            String fileName = FileLoader.getDocumentFileName(sourceMessage.getDocument());
            if (fileName != null && fileName.toLowerCase().contains(query)) {
                MessageObject copy = new MessageObject(currentAccount, sourceMessage.messageOwner, false, false);
                copy.mediaExists = sourceMessage.mediaExists;
                copy.attachPathExists = sourceMessage.attachPathExists;
                copy.isDownloadingFile = true;
                copy.setQuery(searchQuery);
                result.add(copy);
            }
        }
        return result;
    }

    private boolean isEmptyDownloads() {
        DownloadController controller = DownloadController.getInstance(currentAccount);
        return controller.downloadingFiles.isEmpty()
                && controller.recentDownloadingFiles.isEmpty()
                && controller.failedDownloadingFiles.isEmpty();
    }

    private void updateListInternal(boolean animated, ArrayList<MessageObject> current, ArrayList<MessageObject> recent, ArrayList<MessageObject> failed) {
        updateRows(current, recent, failed);
        adapter.notifyDataSetChanged();
        refreshVisibleDownloadStatuses();
    }

    private void copyVisibleTasks(ArrayList<MessageObject> destination, ArrayList<MessageObject> source) {
        destination.clear();
        for (int i = 0; i < source.size(); i++) {
            MessageObject object = source.get(i);
            if (!object.isRoundVideo() && !object.isVoice()) {
                destination.add(object);
            }
        }
    }

    private void updateRows(ArrayList<MessageObject> current, ArrayList<MessageObject> recent, ArrayList<MessageObject> failed) {
        copyVisibleTasks(currentLoadingFiles, current);
        copyVisibleTasks(recentLoadingFiles, recent);
        copyVisibleTasks(failedLoadingFiles, failed);

        rowCount = 0;
        downloadedVideosRow = -1;
        downloadingFilesHeader = -1;
        downloadingFilesStartRow = -1;
        downloadingFilesEndRow = -1;
        recentFilesHeader = -1;
        recentFilesStartRow = -1;
        recentFilesEndRow = -1;
        failedFilesHeader = -1;
        failedFilesStartRow = -1;
        failedFilesEndRow = -1;
        hasCurrentDownload = false;

        if (selectedDownloadTab == TAB_DOWNLOADING) {
            if (!currentLoadingFiles.isEmpty()) {
                downloadingFilesHeader = rowCount++;
                downloadingFilesStartRow = rowCount;
                rowCount += currentLoadingFiles.size();
                downloadingFilesEndRow = rowCount;
                for (int i = 0; i < currentLoadingFiles.size(); i++) {
                    if (FileLoader.getInstance(currentAccount).isLoadingFile(currentLoadingFiles.get(i).getFileName())) {
                        hasCurrentDownload = true;
                        break;
                    }
                }
            }
        } else if (selectedDownloadTab == TAB_COMPLETED) {
            if (TextUtils.isEmpty(searchQuery)) {
                downloadedVideosRow = rowCount++;
            }
            if (!recentLoadingFiles.isEmpty()) {
                recentFilesHeader = rowCount++;
                recentFilesStartRow = rowCount;
                rowCount += recentLoadingFiles.size();
                recentFilesEndRow = rowCount;
            }
        } else if (!failedLoadingFiles.isEmpty()) {
            failedFilesHeader = rowCount++;
            failedFilesStartRow = rowCount;
            rowCount += failedLoadingFiles.size();
            failedFilesEndRow = rowCount;
        }
    }
    private CharSequence getDownloadStatusText(MessageObject messageObject) {
        if (selectedDownloadTab == TAB_COMPLETED || messageObject == null) {
            return null;
        }
        DownloadController controller = DownloadController.getInstance(currentAccount);
        DownloadController.DownloadTaskInfo info = controller.getDownloadTaskInfo(messageObject);
        if (selectedDownloadTab == TAB_FAILED) {
            int reason = info == null ? 0 : info.failureReason;
            if (reason == -1) {
                return "\u5931\u8d25 \u00b7 \u5b58\u50a8\u7a7a\u95f4\u4e0d\u8db3\u6216\u5b58\u50a8\u4e0d\u53ef\u5199";
            } else if (reason == 2) {
                return "\u5931\u8d25 \u00b7 \u670d\u52a1\u5668\u91cd\u8bd5\u6b21\u6570\u5df2\u8fbe\u4e0a\u9650";
            }
            return "\u5931\u8d25 \u00b7 \u7f51\u7edc\u9519\u8bef\u6216\u6587\u4ef6\u5df2\u4e0d\u53ef\u7528";
        }
        if (info == null) {
            return FileLoader.getInstance(currentAccount).isLoadingFile(messageObject.getFileName())
                    ? "\u6b63\u5728\u8fde\u63a5" : "\u7b49\u5f85\u4e0b\u8f7d";
        }

        String progressText;
        long totalBytes = info.totalBytes > 0 ? info.totalBytes : messageObject.getDocument().size;
        if (info.downloadedBytes > 0 && totalBytes > 0) {
            progressText = AndroidUtilities.formatFileSize(info.downloadedBytes) + " / " + AndroidUtilities.formatFileSize(totalBytes);
        } else {
            progressText = AndroidUtilities.formatFileSize(totalBytes);
        }

        if (info.state == DownloadController.DOWNLOAD_STATE_PAUSED) {
            return progressText + " \u00b7 \u5df2\u6682\u505c";
        }
        if (!FileLoader.getInstance(currentAccount).isLoadingFile(messageObject.getFileName())) {
            return progressText + " \u00b7 \u7b49\u5f85\u4e0b\u8f7d";
        }

        long now = System.currentTimeMillis();
        if (info.bytesPerSecond <= 0 || info.lastProgressTime == 0 || now - info.lastProgressTime > 3500) {
            return progressText + " \u00b7 \u6b63\u5728\u8fde\u63a5";
        }
        StringBuilder status = new StringBuilder(progressText)
                .append(" \u00b7 ")
                .append(AndroidUtilities.formatFileSize(info.bytesPerSecond))
                .append("/s");
        if (info.estimatedSeconds > 0) {
            status.append(" \u00b7 \u5269\u4f59").append(formatRemainingTime(info.estimatedSeconds));
        }
        return status;
    }

    private String formatRemainingTime(long seconds) {
        if (seconds < 60) {
            return Math.max(1, seconds) + "\u79d2";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            long remainingSeconds = seconds % 60;
            return minutes + "\u5206" + (remainingSeconds == 0 ? "" : remainingSeconds + "\u79d2");
        }
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        return hours + "\u5c0f\u65f6" + (remainingMinutes == 0 ? "" : remainingMinutes + "\u5206");
    }

    private void refreshVisibleDownloadStatuses() {
        if (recyclerListView == null || selectedDownloadTab == TAB_COMPLETED) {
            return;
        }
        for (int i = 0; i < recyclerListView.getChildCount(); i++) {
            View child = recyclerListView.getChildAt(i);
            if (child instanceof Cell) {
                ((Cell) child).sharedDocumentCell.refreshDownloadStatus();
            }
        }
    }
    public void search(String query) {
        searchQuery = query;
        update(false);
    }

    private class DownloadsAdapter extends RecyclerListView.SelectionAdapter {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new GraySectionCell(parent.getContext());
            } else if (viewType == 1){
                Cell sharedDocumentCell = new Cell(parent.getContext());
                view = sharedDocumentCell;
            } else if (viewType == 3) {
                view = new TextCell(parent.getContext());
            } else {
                SharedAudioCell sharedAudioCell = new SharedAudioCell(parent.getContext()) {
                    @Override
                    public boolean needPlayMessage(MessageObject messageObject) {
                        return MediaController.getInstance().playMessage(messageObject);
                    }
                };
                view = sharedAudioCell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);

        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == 0) {
                GraySectionCell graySectionCell = (GraySectionCell) holder.itemView;
                if (position == downloadingFilesHeader) {
                    String header = LocaleController.getString(R.string.Downloading);
                    graySectionCell.setText(header, hasCurrentDownload ? LocaleController.getString(R.string.PauseAll) : LocaleController.getString(R.string.ResumeAll), view -> {
                        DownloadController controller = DownloadController.getInstance(currentAccount);
                        if (hasCurrentDownload) {
                            controller.pauseAllDownloads();
                        } else {
                            controller.resumeAllDownloads();
                        }
                        update(true);
                    });
                } else if (position == recentFilesHeader) {
                    graySectionCell.setText(LocaleController.getString(R.string.RecentlyDownloaded), LocaleController.getString(R.string.Settings),
                            view -> DownloadsInfoBottomSheet.show(parentActivity, parentFragment)
                    );
                } else if (position == failedFilesHeader) {
                    graySectionCell.setText("\u5931\u8d25", LocaleController.getString(R.string.Retry), view -> {
                        DownloadController.getInstance(currentAccount).retryAllFailedDownloads();
                        selectedDownloadTab = TAB_DOWNLOADING;
                        downloadTabs.setInitialTabId(TAB_DOWNLOADING);
                        update(false);
                    });
                }
            } else if (type == 3) {
                ((TextCell) holder.itemView).setTextAndIcon("\u5df2\u4e0b\u8f7d\u89c6\u9891", R.drawable.msg_video, true);
            } else {
                MessageObject messageObject = getMessage(position);
                if (messageObject != null) {
                    boolean showReorder = uiCallback.actionModeShowing() && position >= downloadingFilesStartRow && position < downloadingFilesEndRow;
                    if (type == 1) {
                        Cell view = (Cell) holder.itemView;
                        view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                        int oldId = view.sharedDocumentCell.getMessage() == null ? 0 : view.sharedDocumentCell.getMessage().getId();
                        view.sharedDocumentCell.setDocument(messageObject, true);
                        messageHashIdTmp.set(view.sharedDocumentCell.getMessage().getId(), view.sharedDocumentCell.getMessage().getDialogId());
                        view.sharedDocumentCell.setChecked(uiCallback.isSelected(messageHashIdTmp), oldId == messageObject.getId());
                        view.sharedDocumentCell.showReorderIcon(showReorder, oldId == messageObject.getId());
                    } else if (type == 2) {
                        SharedAudioCell sharedAudioCell = (SharedAudioCell) holder.itemView;
                        int oldId = sharedAudioCell.getMessage() == null ? 0 : sharedAudioCell.getMessage().getId();
                        sharedAudioCell.setMessageObject(messageObject, true);
                        messageHashIdTmp.set(sharedAudioCell.getMessage().getId(), sharedAudioCell.getMessage().getDialogId());
                        sharedAudioCell.setChecked(uiCallback.isSelected(messageHashIdTmp), oldId == messageObject.getId());
                        sharedAudioCell.showReorderIcon(showReorder, oldId == messageObject.getId());
                    }
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == downloadedVideosRow) {
                return 3;
            }
            if (position == downloadingFilesHeader || position == recentFilesHeader || position == failedFilesHeader) {
                return 0;
            }
            MessageObject messageObject = getMessage(position);
            if (messageObject == null) {
                return 1;
            }
            if (selectedDownloadTab == TAB_COMPLETED && messageObject.isMusic()) {
                return 2;
            }
            return 1;
        }

        private MessageObject getMessage(int position) {
            if (position >= downloadingFilesStartRow && position < downloadingFilesEndRow) {
                return currentLoadingFiles.get(position - downloadingFilesStartRow);
            } else if (position >= recentFilesStartRow && position < recentFilesEndRow) {
                return recentLoadingFiles.get(position - recentFilesStartRow);
            } else if (position >= failedFilesStartRow && position < failedFilesEndRow) {
                return failedLoadingFiles.get(position - failedFilesStartRow);
            }
            return null;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 1 || holder.getItemViewType() == 2 || holder.getItemViewType() == 3;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.onDownloadingFilesChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.premiumFloodWaitReceived);
        if (getVisibility() == View.VISIBLE) {
            DownloadController.getInstance(currentAccount).clearUnviewedDownloads();
        }
        checkFilesExist();
        update(false);
        AndroidUtilities.cancelRunOnUIThread(downloadStatusRefreshRunnable);
        AndroidUtilities.runOnUIThread(downloadStatusRefreshRunnable, 1000);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.onDownloadingFilesChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.premiumFloodWaitReceived);
        AndroidUtilities.cancelRunOnUIThread(downloadStatusRefreshRunnable);
    }


    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.onDownloadingFilesChanged) {
            if (getVisibility() == View.VISIBLE) {
                DownloadController.getInstance(currentAccount).clearUnviewedDownloads();
            }
            update(true);
        } else if (id == NotificationCenter.premiumFloodWaitReceived) {
            checkItemsFloodWait();
        }
    }

    private class Cell extends FrameLayout {

        SharedDocumentCell sharedDocumentCell;

        public Cell(@NonNull Context context) {
            super(context);
            sharedDocumentCell = new SharedDocumentCell(context, SharedDocumentCell.VIEW_TYPE_GLOBAL_SEARCH);
            sharedDocumentCell.rightDateTextView.setVisibility(View.GONE);
            sharedDocumentCell.setDownloadStatusProvider(SearchDownloadsContainer.this::getDownloadStatusText);
            addView(sharedDocumentCell);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            sharedDocumentCell.onInitializeAccessibilityNodeInfo(info);
        }
    }

    public void setUiCallback(FilteredSearchView.UiCallback callback) {
        this.uiCallback = callback;
    }

    public void setKeyboardHeight(int keyboardSize, boolean animated) {
        emptyView.setKeyboardHeight(keyboardSize, animated);
    }


    public class TouchHelperCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return uiCallback.actionModeShowing();
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            boolean canMove = viewHolder.getAdapterPosition() >= downloadingFilesStartRow && viewHolder.getAdapterPosition() < downloadingFilesEndRow;
            if (!canMove) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
            boolean canMove = target.getAdapterPosition() >= downloadingFilesStartRow && target.getAdapterPosition() < downloadingFilesEndRow;
//            boolean canMove = getUserConfig().isPremium() || !((target.itemView instanceof FiltersSetupActivity.FilterCell) && ((FiltersSetupActivity.FilterCell) target.itemView).currentFilter.isDefault());
            if (!canMove) {
                return false;
            }
            int fromIndex = source.getAdapterPosition();
            int toIndex = target.getAdapterPosition();

            int idx1 = fromIndex - downloadingFilesStartRow;
            int idx2 = toIndex - downloadingFilesStartRow;
            currentLoadingFiles.indexOf(fromIndex - downloadingFilesStartRow);
            currentLoadingFiles.get(fromIndex - downloadingFilesStartRow);

            MessageObject o1 = currentLoadingFiles.get(idx1);
            MessageObject o2 = currentLoadingFiles.get(idx2);
//            int temp = filter1.order;
//            filter1.order = filter2.order;
//            filter2.order = temp;
            currentLoadingFiles.set(idx1, o2);
            currentLoadingFiles.set(idx2, o1);

            DownloadController.getInstance(currentAccount).swapLoadingPriority(o1, o2);

            adapter.notifyItemMoved(fromIndex, toIndex);
            return false;
        }

        @Override
        public void onChildDraw(Canvas c, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                recyclerListView.cancelClickRunnables(false);
                viewHolder.itemView.setPressed(true);
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {

        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
        }
    }

    public void checkItemsFloodWait() {
        if (UserConfig.getInstance(currentAccount).isPremium()) return;
        if (recyclerListView == null) return;
        for (int i = 0; i < recyclerListView.getChildCount(); ++i) {
            try {
                View child = recyclerListView.getChildAt(i);
                if (!(child instanceof Cell)) continue;
                MessageObject messageObject = ((Cell) child).sharedDocumentCell.getMessage();
                if (messageObject == null) continue;
                if (FileLoader.getInstance(currentAccount).checkLoadCaughtPremiumFloodWait(messageObject.getFileName())) {
                    showPremiumFloodWaitBulletin(false);
                    return;
                } else if (FileLoader.getInstance(currentAccount).checkLoadCaughtPremiumFloodWait(messageObject.getFileName())) {
                    showPremiumFloodWaitBulletin(true);
                    return;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public void showPremiumFloodWaitBulletin(final boolean isUpload) {
        if (parentFragment == null || !recyclerListView.isAttachedToWindow()) return;

        final long now = System.currentTimeMillis();
        if (now - ConnectionsManager.lastPremiumFloodWaitShown < 1000L * MessagesController.getInstance(currentAccount).uploadPremiumSpeedupNotifyPeriod) {
            return;
        }
        ConnectionsManager.lastPremiumFloodWaitShown = now;
        if (UserConfig.getInstance(currentAccount).isPremium() || MessagesController.getInstance(currentAccount).premiumFeaturesBlocked()) {
            return;
        }

        final float n;
        if (isUpload) {
            n = MessagesController.getInstance(currentAccount).uploadPremiumSpeedupUpload;
        } else {
            n = MessagesController.getInstance(currentAccount).uploadPremiumSpeedupDownload;
        }
        SpannableString boldN = new SpannableString(Double.toString(Math.round(n * 10) / 10.0).replaceAll("\\.0$", ""));
        boldN.setSpan(new TypefaceSpan(AndroidUtilities.bold()), 0, boldN.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (parentFragment.hasStoryViewer()) return;
        BulletinFactory.of(parentFragment).createSimpleBulletin(
                R.raw.speed_limit,
                LocaleController.getString(isUpload ? R.string.UploadSpeedLimited : R.string.DownloadSpeedLimited),
                AndroidUtilities.replaceCharSequence("%d", AndroidUtilities.premiumText(LocaleController.getString(isUpload ? R.string.UploadSpeedLimitedMessage : R.string.DownloadSpeedLimitedMessage), () -> {
                    parentFragment.presentFragment(new PremiumPreviewFragment(isUpload ? "upload_speed" : "download_speed"));
                }), boldN)
        ).setDuration(8000).show(false);
    }
}
