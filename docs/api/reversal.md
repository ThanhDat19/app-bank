# BL-010 — Hoàn giao dịch toàn phần

Nối tiếp BL-008 tại `e3131d7`, dependency PR #21. User ngày29/10/2026 yêu cầu tiếp tục nghiệp vụ,
giữ cấu trúc hiện tại. Owner merge dependency trước PR mới; AI không tự merge.

**Cập nhật BL-019/V16:** Phải tạo REVERSAL intent và có OPERATOR khác approve qua
[maker-checker](maker-checker.md). Legacy direct POST trả409 OPERATION_APPROVAL_REQUIRED.
Financial rules dưới đây vẫn áp dụng khi approve; existing GET receipts giữ nguyên quyền truy cập.

## Rules và actors

| Rule | Contract |
| --- | --- |
| REV-01 | Verified JWT, OPERATOR maker + checker khác identity; CUSTOMER không tự hoàn tiền. |
| REV-02 | Chỉ POSTED cash-in/internal transfer có public receipt tương ứng. |
| REV-03 | Một original chỉ có một reversal hoàn tất, kể cả khác operator/key. |
| REV-04 | Full inverse bằng journal mới: đúng accounts/currency/amount, đảo DEBIT↔CREDIT của cả hai lines. |
| REV-05 | Original journal/postings/receipt bất biến, vẫn POSTED; reversal có link originalJournalId. |
| REV-06 | Accounts phải ACTIVE/provisioned; giữ no-negative/max-balance guards, không overdraft/state override. |
| REV-07 | Reason OPERATOR_ERROR hoặc DUPLICATE_PROCESSING, lưu cùng verified actor/timestamp. |
| REV-08 | Claim/hash/key, original serialization, inverse journal/postings/receipt cùng READ COMMITTED transaction. |
| REV-09 | Maker create intent replay201 state hiện tại; checker approve retry200 stored result; changed intent409. |
| REV-10 | GET reversal receipt chỉ checker/legacy creator; maker đọc result qua operation request. |
| REV-11 | Completed receipt/key append-only; failed approval rollback tiền và reversal claim, immutable PENDING intent vẫn giữ. |

Không partial reversal, reversal-of-reversal hoặc ADJUSTMENT reversal. Request không nhận amount/postings/actor;
unknown fields bị từ chối để tránh hiểu nhầm partial thành full. Hai OPERATOR có thể yêu cầu/duyệt original của
customer/operator khác khi có journalId từ receipt. Chưa phải dispute workflow hay chính sách ngân hàng thật.

Cash-in inverse: customer deposit DEBIT, cash clearing CREDIT. Transfer inverse: original source CREDIT,
original destination DEBIT. Nếu recipient đã chi tiền hoặc original source đã được nạp tới max, reversal có thể
thiếu tiền/overflow; reject toàn bộ, không tự dùng tài khoản khác, mở lại account hoặc đảo một phần.

## REST

JWT/Problem Details/correlation/no-store theo conventions. Required Idempotency-Key:1–128 ASCII `[A-Za-z0-9._:-]`,
case-sensitive, không chứa PII. Public key scope là maker issuer+subject+operations, riêng cash-in/transfer.

| Method/path | Kết quả |
| --- | --- |
| POST `/api/v1/operations/requests` type REVERSAL |201 PENDING intent + Location; chưa ghi tiền |
| POST `/api/v1/operations/requests/{id}/approve` |200 APPROVED + `result` receipt, checker khác maker |
| POST `/api/v1/operations/reversals` |409 OPERATION_APPROVAL_REQUIRED; legacy disabled |
| GET `/api/v1/operations/reversals/{id}` |200 receipt của checker/legacy creator |

```http
POST /api/v1/operations/requests
Authorization: Bearer <maker operator access token>
Idempotency-Key: demo-reversal-010-a
Content-Type: application/json

{"type":"REVERSAL","targetId":"de5fa6c9-57e3-43f1-8903-9beb23f432ad","reason":"OPERATOR_ERROR"}
```

Lưu operation `id`; checker khác maker POST `/{id}/approve` với `{"expectedVersion":0}`.
`result` chứa receipt sau (không phải toàn bộ operation response):

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

Amount response là exact integer VND string tối đa20 digits, lấy từ original. Internal reversal SHA-256 input:
`reversal:v1\n<canonical original UUID>\n<reason>`. JSON order/whitespace, UUID case, token renewal/correlation
không đổi hash. Internal key là operation UUID, actor là checker; public intent hash/key theo BL-019.
Đổi operation không cho phép hoàn original lần hai.

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
replay. Approved operation result replay được sau account close/freeze/balance thay đổi bằng đúng checker/id.
Original cash-in/transfer retry vẫn trả receipt gốc, không nạp/chuyển thêm sau reversal.
Failed approval vẫn PENDING; phục hồi precondition rồi retry approve. Target/reason mới phải có intent/key mới.

## Transaction, database và recovery

V7 chỉ thêm reversal data/guards, không sửa V1–V6 hoặc seed tiền. Original FK deferred cho phép application trả404.
Tại V16 approval: lock operation → distinct checker → transaction execution claim →
reversal claim INSERT ON CONFLICT → lock claim → compare hash/replay → original FOR NO KEY UPDATE
→ public receipt eligibility → already-reversed check → ordered bank/ledger locks → final money/state guards
→ inverse POSTED journal → complete receipt → operation APPROVED/result/history/audit → commit.
Không network dưới locks, không batch commands.

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
