package com.iaf.console.platform.client;

/** The filters of a resource listing (P-05): type, owner, status, account, wfrn, visibility, and the page. */
public record ResourceFilter(String type, String owner, String status, String account, String wfrn, String visibility, Integer limit, String after) {

    private static final ResourceFilter ALL = new ResourceFilter(null, null, null, null, null, null, null, null);

    public static ResourceFilter all() {
        return ALL;
    }

    public static ResourceFilter ofType(String type) {
        return new ResourceFilter(type, null, null, null, null, null, null, null);
    }

    public ResourceFilter withType(String t) {
        return new ResourceFilter(t, owner, status, account, wfrn, visibility, limit, after);
    }

    public ResourceFilter withOwner(String o) {
        return new ResourceFilter(type, o, status, account, wfrn, visibility, limit, after);
    }

    public ResourceFilter withStatus(String s) {
        return new ResourceFilter(type, owner, s, account, wfrn, visibility, limit, after);
    }

    public ResourceFilter withAccount(String a) {
        return new ResourceFilter(type, owner, status, a, wfrn, visibility, limit, after);
    }

    public ResourceFilter withWfrn(String w) {
        return new ResourceFilter(type, owner, status, account, w, visibility, limit, after);
    }

    public ResourceFilter withVisibility(String v) {
        return new ResourceFilter(type, owner, status, account, wfrn, v, limit, after);
    }

    /** Opt into paging: at most {@code size} items (1 to 500) and a Link header while there is more. */
    public ResourceFilter withLimit(int size) {
        return new ResourceFilter(type, owner, status, account, wfrn, visibility, size, after);
    }

    /** The next page, from the cursor of the previous page. */
    public ResourceFilter withAfter(String cursor) {
        return new ResourceFilter(type, owner, status, account, wfrn, visibility, limit, cursor);
    }
}
