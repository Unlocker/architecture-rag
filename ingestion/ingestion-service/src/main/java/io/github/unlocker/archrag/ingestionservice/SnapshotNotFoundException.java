package io.github.unlocker.archrag.ingestionservice;

/** У источника нет ни одного маркера {@code snapshot-complete}. */
public class SnapshotNotFoundException extends RuntimeException {

  public SnapshotNotFoundException() {
    super("no completed snapshot for the source");
  }
}
