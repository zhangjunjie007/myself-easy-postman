package com.laker.postman.panel.collections.tree.adapter;

import com.laker.postman.collection.model.CollectionDocument;
import com.laker.postman.service.collections.CollectionFilePersistence;
import com.laker.postman.service.collections.DefaultCollectionDocumentFactory;
import lombok.extern.slf4j.Slf4j;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class SwingCollectionTreePersistence {
    private final CollectionFilePersistence filePersistence;
    private final DefaultMutableTreeNode rootTreeNode;
    private final DefaultTreeModel treeModel;
    private volatile CompletableFuture<Void> treeLoaded = new CompletableFuture<>();

    public SwingCollectionTreePersistence(String filePath, DefaultMutableTreeNode rootTreeNode, DefaultTreeModel treeModel) {
        this(new CollectionFilePersistence(filePath), rootTreeNode, treeModel);
    }

    SwingCollectionTreePersistence(CollectionFilePersistence filePersistence,
                                   DefaultMutableTreeNode rootTreeNode,
                                   DefaultTreeModel treeModel) {
        this.filePersistence = filePersistence;
        this.rootTreeNode = rootTreeNode;
        this.treeModel = treeModel;
    }

    public void exportCurrentTree(File fileToSave) throws IOException {
        filePersistence.export(currentDocument(), fileToSave);
    }

    /** Loads the collection and signals readiness only after its nodes have replaced the tree. */
    public void loadIntoTree() {
        if (treeLoaded.isDone()) treeLoaded = new CompletableFuture<>();
        CompletableFuture<Void> loading = treeLoaded;
        try {
            applyDocument(filePersistence.loadOrCreate(this::defaultDocument));
            loading.complete(null);
        } catch (Exception e) {
            loading.completeExceptionally(e);
            log.error("Error loading request collections", e);
        }
    }

    /**
     * Allows toolbox imports to wait off EDT for collection loading instead of racing a tree replacement.
     * @return completion of the current load, exceptional when existing collections could not be read
     */
    public CompletableFuture<Void> whenTreeLoaded() {
        return treeLoaded;
    }

    /** Persists the current tree using the existing best-effort save behavior. */
    public void saveCurrentTree() {
        trySaveCurrentTree();
    }

    /**
     * Persists the current tree and exposes write/load-guard failures to import callers.
     * @return true only when the collection was written; false leaves the in-memory tree available
     */
    public boolean trySaveCurrentTree() {
        return filePersistence.save(currentDocument());
    }

    /** Loads the selected workspace file and replaces readiness so a failed switch cannot be imported into. */
    public void switchDataFilePath(String path) {
        CompletableFuture<Void> loading = new CompletableFuture<>();
        treeLoaded = loading;
        try {
            applyDocument(filePersistence.switchFilePathAndLoad(path, this::defaultDocument));
            loading.complete(null);
        } catch (Exception e) {
            loading.completeExceptionally(e);
            log.error("Error switching collection data file", e);
        }
    }

    private CollectionDocument currentDocument() {
        return SwingCollectionTreeDocumentMapper.fromRoot(rootTreeNode);
    }

    private CollectionDocument defaultDocument() {
        return DefaultCollectionDocumentFactory.create();
    }

    private void applyDocument(CollectionDocument document) {
        SwingCollectionTreeDocumentMapper.replaceRootChildren(rootTreeNode, document);
        treeModel.reload(rootTreeNode);
    }
}
