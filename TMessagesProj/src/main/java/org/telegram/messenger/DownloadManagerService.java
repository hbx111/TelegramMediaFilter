package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Application-scoped facade for user initiated media downloads.
 *
 * FileLoader owns the protocol-level range requests, resume ranges and disk writes;
 * DownloadController persists the queue in the existing account database. Keeping
 * those responsibilities there prevents duplicate transfers for the same document.
 */
public final class DownloadManagerService {

    private static final DownloadManagerService[] instances = new DownloadManagerService[UserConfig.MAX_ACCOUNT_COUNT];

    public static DownloadManagerService getInstance(int account) {
        DownloadManagerService instance = instances[account];
        if (instance == null) {
            synchronized (DownloadManagerService.class) {
                instance = instances[account];
                if (instance == null) {
                    instances[account] = instance = new DownloadManagerService(account);
                }
            }
        }
        return instance;
    }

    private final int currentAccount;

    private DownloadManagerService(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public boolean enqueue(MessageObject messageObject) {
        return enqueue(messageObject, FileLoader.PRIORITY_NORMAL);
    }

    public boolean enqueue(MessageObject messageObject, int priority) {
        if (!isDownloadAllowed(messageObject)) {
            return false;
        }
        TLRPC.Document document = messageObject.getDocument();
        if (document == null) {
            return false;
        }
        messageObject.putInDownloadsStore = true;
        FileLoader.getInstance(currentAccount).loadFile(document, messageObject, priority, 0);
        return true;
    }

    public boolean prioritize(MessageObject messageObject) {
        return enqueue(messageObject, FileLoader.PRIORITY_HIGH);
    }

    public void pause(MessageObject messageObject) {
        DownloadController.getInstance(currentAccount).pauseDownload(messageObject);
    }

    public boolean resume(MessageObject messageObject) {
        if (!isDownloadAllowed(messageObject)) {
            return false;
        }
        DownloadController.getInstance(currentAccount).resumeDownload(messageObject);
        return true;
    }

    public boolean retry(MessageObject messageObject) {
        return resume(messageObject);
    }

    public List<MessageObject> getActiveTasks() {
        return Collections.unmodifiableList(new ArrayList<>(DownloadController.getInstance(currentAccount).downloadingFiles));
    }

    public List<MessageObject> getRecentTasks() {
        return Collections.unmodifiableList(new ArrayList<>(DownloadController.getInstance(currentAccount).recentDownloadingFiles));
    }

    public List<MessageObject> getFailedTasks() {
        return Collections.unmodifiableList(new ArrayList<>(DownloadController.getInstance(currentAccount).failedDownloadingFiles));
    }

    private boolean isDownloadAllowed(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getDocument() == null) {
            return false;
        }
        return !messageObject.messageOwner.noforwards
            && !MessagesController.getInstance(currentAccount).isPeerNoForwards(messageObject.getDialogId())
            && !messageObject.isRoundOnce()
            && !messageObject.isVoiceOnce();
    }
}
