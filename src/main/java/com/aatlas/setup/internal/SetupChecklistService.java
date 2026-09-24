package com.aatlas.setup.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workspace's setup checklist: one line per kind of data, done or not, with where to go
 * to finish it.
 *
 * <p>Every count is a plain {@code count(*)} on the owning table, filtered by tenant both
 * explicitly and by row-level security (the connection is bound to the tenant). A line is
 * done as soon as one row exists - the point is "have you loaded this at all", not a data
 * quality score.
 *
 * <p>The first four lines are the connect stepper's steps, in its order: suppliers, products,
 * sales, purchases. Only the product master is required - it is the least a workspace can
 * open on, and a products-only tenant is a finished setup, not a nagging badge. The rest make
 * answers better or open more screens, and are marked recommended.
 */
@Service
class SetupChecklistService {

    private final JdbcTemplate jdbc;

    SetupChecklistService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One thing to do.
     *
     * @param module the rail module whose screen {@code href} opens, or null for screens
     *     everyone can open; the frontend hides lines whose module the person cannot open
     * @param count rows loaded so far
     */
    record Item(
            String key,
            String title,
            String detail,
            boolean done,
            long count,
            boolean required,
            String href,
            String action,
            String module) {
    }

    /** The checklist, with the totals the bell's badge needs. */
    record Checklist(int total, int completed, int requiredOpen, List<Item> items) {
    }

    @Transactional(readOnly = true)
    Checklist checklist() {
        TenantContext.Actor actor = TenantContext.current()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."));
        UUID tenant = actor.tenantId();

        long sales = count("sales_transactions", tenant);
        long products = count("products", tenant);
        // Purchase history is the file (or the sample dataset). A purchase typed in on Buy is an
        // `award` row on the same table: real, but one item, not a history - counting it ticked
        // this line off after a single recorded order and hid it from the bell.
        long purchases = jdbc.queryForObject(
                "select count(*) from purchase_order where tenant_id = ? and source in ('import', 'sample')",
                Long.class, tenant);
        long recordedItems = jdbc.queryForObject(
                "select count(distinct item_number) from purchase_order where tenant_id = ? and source = 'award'",
                Long.class, tenant);
        long suppliers = count("suppliers", tenant);
        long competitors = count("competitor_prices", tenant);
        long stock = count("inventory_positions", tenant);
        long unplaced = jdbc.queryForObject(
                "select count(*) from stores where tenant_id = ? and region_key = 'unassigned' and active",
                Long.class, tenant);
        long people = jdbc.queryForObject(
                "select count(*) from users where tenant_id = ? and status in ('ACTIVE', 'INVITED')",
                Long.class, tenant);
        boolean admin = actor.userId() != null && jdbc.queryForList(
                        "select workspace_role from users where id = ? and tenant_id = ?",
                        String.class, actor.userId(), tenant)
                .stream().anyMatch(r -> !"member".equals(r));

        // The hrefs open the connect stepper at that step (?step=), so the bell drops a person
        // into the same guided flow whether the workspace is open yet or not.
        List<Item> items = new ArrayList<>();
        items.add(new Item("suppliers", "Add your suppliers",
                suppliers > 0 ? "%,d suppliers on the panel.".formatted(suppliers)
                        : "Lead time and on-time rate for each supplier, for ratings, risk and supplier comparison.",
                suppliers > 0, suppliers, false, "/app/connect?step=suppliers", "Add suppliers", "suppliers"));
        items.add(new Item("products", "Add your products",
                products > 0 ? "%,d products in the catalogue.".formatted(products)
                        : "Item master with list price and cost, so margins and landed cost can be worked out.",
                products > 0, products, true, "/app/connect?step=products", "Upload products", null));
        items.add(new Item("sales", "Upload your sales history",
                sales > 0 ? "%,d sales lines loaded.".formatted(sales)
                        : "Recommended prices, forecasts and revenue insights are built from it.",
                sales > 0, sales, false, "/app/connect?step=sales", "Upload sales", null));
        items.add(new Item("purchases", "Upload your purchase history",
                purchases > 0 ? "%,d purchase lines loaded.".formatted(purchases)
                        : recordedItems > 0
                                ? "Only %,d %s recorded by hand on Buy. Upload your purchase orders so every item has a current supplier and a landed cost."
                                        .formatted(recordedItems, recordedItems == 1 ? "item has a purchase" : "items have purchases")
                                : "Real landed costs and your current supplier per item, for the Buy screen and savings.",
                purchases > 0, purchases, false, "/app/connect?step=purchases", "Upload purchases", "buy"));
        items.add(new Item("competitor_prices", "Add competitor prices",
                competitors > 0 ? "%,d competitor prices loaded.".formatted(competitors)
                        : "The best source for the market price. Recommendations are more confident with it.",
                competitors > 0, competitors, false, "/app/data?kind=competitor_prices", "Upload prices", null));
        items.add(new Item("stock", "Add stock on hand",
                stock > 0 ? "Stock counts loaded for %,d item-branch pairs.".formatted(stock)
                        // A product file without an On hand column is the commonest reason this line
                        // stays open next to a products card that says "already loaded" - so say so
                        // rather than repeating what the feature needs.
                        : products > 0
                                ? "Your product file loaded without an On hand column, so there is no stock to read. "
                                        + "Add one and upload it again to unlock weeks of cover, overstock warnings and sell-now-or-hold."
                                : "Weeks of cover, overstock warnings and sell-now-or-hold need it. It is a column in the products file.",
                stock > 0, stock, false, "/app/data?kind=products", "Upload stock", null));
        if (unplaced > 0 || sales > 0) {
            items.add(new Item("branches", "Place your branches on the map",
                    unplaced > 0 ? "%,d %s no region, so %s left out of regional insights."
                            .formatted(unplaced, unplaced == 1 ? "branch has" : "branches have", unplaced == 1 ? "it is" : "they are")
                            : "Every branch has a region.",
                    unplaced == 0, unplaced, false, "/app/stores", "Place branches", "stores"));
        }
        if (admin) {
            items.add(new Item("team", "Invite your team",
                    people > 1 ? "%,d people in the workspace.".formatted(people)
                            : "Give colleagues their own sign-in, with only the modules they need.",
                    people > 1, people, false, "/app/users", "Invite people", null));
        }

        int completed = (int) items.stream().filter(Item::done).count();
        int requiredOpen = (int) items.stream().filter(i -> i.required() && !i.done()).count();
        return new Checklist(items.size(), completed, requiredOpen, items);
    }

    /** Table names are constants from this class, never input, so concatenation is safe here. */
    private long count(String table, UUID tenant) {
        Long n = jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenant);
        return n == null ? 0 : n;
    }
}
