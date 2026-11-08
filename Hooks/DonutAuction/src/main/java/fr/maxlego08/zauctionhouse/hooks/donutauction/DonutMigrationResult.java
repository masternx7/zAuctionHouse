package fr.maxlego08.zauctionhouse.hooks.donutauction;

/**
 * Result of a DonutAuction to zAuctionHouse V4 migration operation.
 */
public class DonutMigrationResult {

    private final boolean success;
    private final int playersImported;
    private final int itemsImported;
    private final int transactionsImported;
    private final int errors;
    private final long durationMs;
    private final String errorMessage;

    private DonutMigrationResult(boolean success, int playersImported, int itemsImported, int transactionsImported, int errors, long durationMs, String errorMessage) {
        this.success = success;
        this.playersImported = playersImported;
        this.itemsImported = itemsImported;
        this.transactionsImported = transactionsImported;
        this.errors = errors;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
    }

    public static DonutMigrationResult success(int playersImported, int itemsImported, int transactionsImported, int errors, long durationMs) {
        return new DonutMigrationResult(true, playersImported, itemsImported, transactionsImported, errors, durationMs, null);
    }

    public static DonutMigrationResult failure(String errorMessage) {
        return new DonutMigrationResult(false, 0, 0, 0, 0, 0, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public int getPlayersImported() {
        return playersImported;
    }

    public int getItemsImported() {
        return itemsImported;
    }

    public int getTransactionsImported() {
        return transactionsImported;
    }

    public int getErrors() {
        return errors;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        if (!success) {
            return "Migration failed: " + errorMessage;
        }
        return String.format("Migration completed in %dms - Players: %d, Items: %d, Transactions: %d, Errors: %d", durationMs, playersImported, itemsImported, transactionsImported, errors);
    }
}
