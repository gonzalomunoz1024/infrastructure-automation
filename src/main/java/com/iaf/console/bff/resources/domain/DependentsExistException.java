package com.iaf.console.bff.resources.domain;

import java.util.List;
import java.util.Map;

import com.iaf.console.bff.common.errors.CodedConflictException;

/**
 * A delete refused because live resources depend on what it would remove: 409
 * {@code dependents.exist}, with the dependents the person may see and the count per account.
 */
public class DependentsExistException extends CodedConflictException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "dependents.exist";

    public DependentsExistException(String message, Map<String, Object> detail) {
        super(CODE, message, detail);
    }

    /** One resource's refusal: its direct dependents and their count per account. */
    public static DependentsExistException of(BlastRadius radius) {
        return new DependentsExistException(sentence(radius), Map.of(
                "dependents", radius.directDependents(),
                "total", radius.direct(),
                "byAccount", radius.byAccount()));
    }

    /** A batch's refusal: the rows that have dependents. */
    public static DependentsExistException ofRows(List<BlastRadius> radii) {
        List<Map<String, Object>> rows = radii.stream().filter(BlastRadius::hasDependents)
                .map(r -> Map.<String, Object>of("wfrn", r.resource().wfrn(), "dependents", r.directDependents(), "total", r.direct()))
                .toList();
        String message = rows.size() == 1 ? "1 of the resources has live dependents; delete or detach them first"
                : rows.size() + " of the resources have live dependents; delete or detach them first";
        return new DependentsExistException(message, Map.of("rows", rows));
    }

    /** "web-01 has 2 live dependents (1 in another account); delete or detach them first". */
    public static String sentence(BlastRadius radius) {
        String name = radius.resource().name() != null ? radius.resource().name() : radius.resource().wfrn();
        long elsewhere = radius.direct() - radius.directDependents().size();
        String count = radius.direct() == 1 ? "1 live dependent" : radius.direct() + " live dependents";
        String where = elsewhere <= 0 ? "" : elsewhere == radius.direct() ? " in other accounts" : " (" + elsewhere + " in other accounts)";
        return name + " has " + count + where + "; delete or detach them first";
    }
}
