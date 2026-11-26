package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Matches Python's structural numeric comparison; the original citation is untouched. */
final class SubmissionQuantityEvidence {
    private SubmissionQuantityEvidence() {}
    static String comparisonQuote(String quote, String title, String row) {
        if (!quote.equals(row) || row.contains("\n") || row.contains("\r")) return quote;
        var cells = new ArrayList<>(Arrays.stream(row.split("\t", -1)).map(String::strip).toList());
        if (cells.size() != 4 || !cells.getFirst().matches("[0-9]+") || !cells.get(1).equals(title)) return quote;
        cells.removeFirst();
        var quantity = Pattern.compile("(?:각\\s*)?(\\d+\\s*(?:부|매))").matcher(cells.get(1));
        if (quantity.matches() && cells.get(2).matches("[가-힣A-Za-z()·ㆍ]+별\\s*(?:각\\s*)?" + Pattern.quote(quantity.group(1))))
            cells.remove(1);
        return String.join("\t", cells);
    }
}
