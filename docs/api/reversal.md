# BL-010 — Hoàn giao dịch toàn phần

Nối tiếp BL-008 tại `e3131d7`, dependency PR #21. User ngày29/10/2026 yêu cầu tiếp tục nghiệp vụ,
giữ cấu trúc hiện tại. Owner merge dependency trước PR mới; AI không tự merge.

## Rules và actors

| Rule | Contract |
| --- | --- |
| REV-01 | Verified JWT, OPERATOR; CUSTOMER không tự hoàn tiền. Maker-checker ở BL-019. |
| REV-02 | Chỉ POSTED cash-in/internal transfer có public receipt tương ứng. |
| REV-03 | Một original chỉ có một reversal hoàn tất, kể cả khác operator/key. |
| REV-04 | Full inverse bằng journal mới: đúng accounts/currency/amount, đảo DEBIT↔CREDIT của cả hai lines. |
| REV-05 | Original journal/postings/receipt bất biến, vẫn POSTED; reversal có link originalJournalId. |
| REV-06 | Accounts phải ACTIVE/provisioned; giữ no-negative/max-balance guards, không overdraft/state override. |
| REV-07 | Reason OPERATOR_ERROR hoặc DUPLICATE_PROCESSING, lưu cùng verified actor/timestamp. |
| REV-08 | Claim/hash/key, original serialization, inverse journal/postings/receipt cùng READ COMMITTED transaction. |
| REV-09 | Same actor/key/payload replay 201; changed payload tr? 409; khác key cho original đã hoàn409. |
| REV-10 | GET receipt chỉ verified operator identity đã tạo; khác operator/missing cùng404. |
| REV-11 | Completed receipt/key append-only, giữ vô thời hạn; lỗi rollback cả key và tiền, không cache failure. |

Không partial reversal, reversal-of-reversal hoặc ADJUSTMENT reversal. Request không nhận amount/postings/actor;
unknown fields bị từ chối để tránh hiểu nhầm partial thành full. OPERATOR có thể hoàn original của customer/operator
khác khi có journalId từ receipt, theo policy mô phỏng trực tiếp; chưa phải maker-checker/dispute workflow thật.

Cash-in inverse: customer deposit DEBIT, cash clearing CREDIT. Transfer inverse: original source CREDIT,
original destination DEBIT. Nếu recipient đã chi tiền hoặc original source đã được nạp tới max, reversal có thể
thiếu tiền/overflow; reject toàn bộ, không tự dùng tài khoản khác, mở lại account hoặc đảo một phần.

## REST

JWT/Problem Details/correlation/no-store theo conventions. Required Idempotency-Key:1–128 ASCII `[A-Za-z0-9._:-]`,
case-sensitive, không chứa PII. Scope issuer+subject+reversal operation, riêng namespaces cash-in/transfer.

| Method/path | Kết quả |
| --- | --- |
| POST `/api/v1/operations/reversals` | 201 POSTED receipt + Location; replay cùng201/body/Location |
| GET `/api/v1/operations/reversals/{id}` | 200 original receipt của cùng operator identity |

```http
POST /api/v1/operations/reversals
Authorization: Bearer <operator access token>
Idempotency-Key: demo-reversal-010-a
Content-Type: application/json

{"originalJournalId":"de5fa6c9-57e3-43f1-8903-9beb23f432ad","reason":"OPERATOR_ERROR"}
```

```json
{
  "id":"ba623c43-55b1-43ba-8323-8c3ba2bf609c",
  "originalJournalId":"de5fa6c9-57e3-43f1-8903-9beb23f432ad",
  "journalId":"ab583726-2770-4ebf-9e5d-2b791d8c965f",
  "originalKind":"INTERNAL_TRANSFER",
  "amount":"100000",
  "currency":"VND",
  "reason":"OPERATOR_ERROR",
  "status":"POSTED",
  "postedAt":"2026-10-29T03:00:00.123456Z"
}
```

Amount response là exact integer VND string tối đa20 digits, lấy từ original. SHA-256 UTF-8 hash input:
`reversal:v1\n<canonical original UUID>\n<reason>`. JSON order/whitespace, UUID case, token renewal/correlation
không đổi hash. Đổi actor/key là command khác nhưng vẫn không hoàn được original lần hai.

| HTTP/code | Điều kiện |
| --- | --- |
| 400 INVALID_REQUEST | Thiếu key, malformed JSON/UUID, unknown amount/postings/actor fields |
| 400 VALIDATION_FAILED | Missing/null original/reason, reason/key invalid |
| 401 UNAUTHENTICATED | Missing/invalid JWT |
| 403 ACCESS_DENIED | Thiếu OPERATOR |
| 404 REVERSAL_ORIGINAL_NOT_FOUND | Journal gốc không tồn tại |
| 404 REVERSAL_NOT_FOUND | Receipt missing hoặc khác creator identity |
| 409 IDEMPOTENCY_CONFLICT | Cùng actor/key, đổi original/reason hợp lệ |
| 409 REVERSAL_ALREADY_EXISTS | Original đã hoàn qua key/operator khác |
| 409 ACCOUNT_STATE_INVALID | Account bị tác động FROZEN/CLOSED |
| 422 REVERSAL_NOT_SUPPORTED | ADJUSTMENT/REVERSAL, không POSTED, internal journal không có public receipt |
| 422 LEDGER_INSUFFICIENT_FUNDS / LEDGER_BALANCE_LIMIT | Debit âm hoặc credit vượt max20digits |
| 422 LEDGER_LINK_INCONSISTENT | Marker/link bất nhất, không bypass guard |
| 500 INTERNAL_ERROR | Unexpected DB/commit failure; không lộ SQL/key/amount/identity |

Internal validation còn có IDEMPOTENCY_KEY_INVALID/REVERSAL_REQUEST_INVALID (422). Auth/validation chạy trước
replay; receipt committed replay được sau account close/freeze hoặc balance thay đổi. Original cash-in/transfer
retry vẫn trả receipt gốc, không nạp/chuyển thêm sau reversal. Lỗi không cache, có thể sửa precondition rồi retry key cũ.

## Transaction, database và recovery

V7 chỉ thêm reversal data/guards, không sửa V1–V6 hoặc seed tiền. Original FK deferred cho phép application trả404.
Flow: claim INSERT ON CONFLICT → lock claim → compare hash/replay → original FOR NO KEY UPDATE
→ public receipt eligibility → already-reversed check → ordered bank/ledger locks → final money/state guards
→ inverse POSTED journal → complete receipt → commit. Không network dưới locks, không batch commands.

Original lock serialize mọi key/operator cho cùng original. NO KEY UPDATE tương thích FK KEY SHARE;
FOR UPDATE có thể deadlock competing claims. Original lock có trước bank/ledger; writers hiện tại không khóa
lại original sau bank locks. Outer transaction nhiều financial commands cần coordinator pre-lock union riêng.

Partial unique index original_journal_id WHERE journal_id IS NOT NULL bảo vệ once-only độc lập Java.
Pending claims khác keys có thể tồn tại trong transaction chưa commit, nhưng deferred guard cấm commit pending.
Receipt guard kiểm tra eligible original, status/kind/reference/actor/time/amount và exact inverse của hai postings.
Trigger phía ledger cũng cấm journal REVERSAL commit thiếu receipt. Completed receipt không update/delete/truncate.

Timeout không chứng minh rollback: retry cùng actor/key/payload, hoặc GET khi biết receipt ID. Không đổi key hoặc
xóa journal/receipt để sửa mất response. Migration/recovery forward-only, không reset volume. DB owner vẫn có thể
disable triggers; chưa production DB role separation/tamper evidence.

## BL-009 — Concurrency và lost response

ReversalIT chạy24 writers: 8 cash-in, 8 A→B, 8 B→A; tất cả commit và tổng money chỉ tăng theo cash-in.
Đây là correctness stress nhỏ, không throughput/SLO benchmark. Reversal races bao gồm nhiều keys/operators,
spend/freeze/close và fault tại finalize/receipt/deferred commit.

Lost-response tests dùng HTTP proxy thật: forward qua production JWT/API, đợi backend201 sau commit,
rồi đóng socket trước response headers. Client nhận IOException, retry trực tiếp cùng key và nhận đúng receipt,
không thêm debit/credit. Test riêng transfer và reversal, không chỉ bỏ qua một response client đã nhận.

ReversalMigrationIT bypass Java: V6 có tiền/receipts→V7 giữ nguyên, reject partial/direction/amount/reference/actor/
kind/time/eligibility/orphan/pending, exact inverse commit và immutable receipt. Re-fund recipient để đủ tiền đảo
lần hai rồi kiểm tra unique original reject độc lập insufficient-funds. [Bằng chứng](../reviews/BL-009-010.md).
