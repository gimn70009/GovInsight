# HWP renderer regression fixtures

Source: edwardkim/rhwp tag v0.8.7, MIT license (LICENSE retained).

- paired.hwp: https://github.com/edwardkim/rhwp/blob/v0.8.7/fuzz/corpus/parse_hwp/issue1510_coanchored_float_tables.hwp
- paired.hwpx: https://github.com/edwardkim/rhwp/blob/v0.8.7/fuzz/corpus/parse_hwpx/issue1510_coanchored_float_tables.hwpx

Synthetic A11/A12/A21/A22 and B11/B12/B21/B22 table content; no external API is used.

HWP author and document-option metadata are anonymized; document content, layout, and all other streams are unchanged.
