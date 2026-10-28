# BL-007 — Nạp tiền mô phỏng có idempotency

BL-006/PR #17 đã merge vào main `7c72584`. BL-007 dùng ledger đã có, không nhận arbitrary journal từ client.
Các policy dưới đây là phạm vi triển khai để owner review trước merge. Chỉ dùng tiền/dữ liệu mô phỏng.

## Actors và rules

| Rule | Contract |
| --- | --- |
| CIN-01 | JWT đã verify, role OPERATOR mới tạo/đọc receipt. CUSTOMER không tự nạp tiền. |
| CIN-02 | Đích là UUID bank account ACTIVE, đã provision Ledger; không tự provision hoặc đổi trạng thái. |
| CIN-03 | VND; amount là JSON string số nguyên dương, canonical, tối đa 20 chữ số; tổng balance không vượt max Ledger. |
| CIN-04 | DEBIT `VND_CASH_CLEARING` ASSET, CREDIT customer deposit LIABILITY, cùng amount; một journal/command. |
| CIN-05 | Key scope = verified issuer + subject + cash-in operation; unique constraint chặn trùng, phân biệt hoa/thường. |
| CIN-06 | SHA-256 của `cash-in:v1\n<UUID canonical>\nVND\n<amount canonical>`; cùng key/hash replay, khác payload trả 409. |
| CIN-07 | Claim, journal, postings, receipt commit cùng READ COMMITTED transaction. Không commit claim thiếu journal POSTED. |
| CIN-08 | Retry trả nguyên receipt, kể cả account bị freeze/close sau giao dịch; kiểm tra quyền vẫn chạy mỗi lần. |
| CIN-09 | GET receipt chỉ cho operator identity đã tạo; identity khác và ID không tồn tại cùng 404. |
| CIN-10 | Receipt/key không sửa/xóa/truncate, không TTL trong MVP. Business/DB/commit error rollback key và toàn bộ tiền. |

Đây là nghiệp vụ mô phỏng OPERATOR trực tiếp nạp vào customer account. Không có maker-checker, xác nhận tiền
thực từ đối tác, limits theo ngày, reversal, transfer, holds hoặc available balance. Maker-checker theo BL-019.
Đổi key hoặc operator identity là command mới, có thể cộng thêm tiền; idempotency không dedupe ý định giữa các actor.

## REST và ví dụ

JWT/Problem Details/correlation/no-store theo conventions hiện có.

| Method/path | Kết quả |
| --- | --- |
| POST `/api/v1/operations/cash-ins` | 201 immutable POSTED receipt; `Location` trỏ GET receipt, cả lần đầu và replay |
| GET `/api/v1/operations/cash-ins/{id}` | 200 receipt do cùng verified issuer/subject tạo |

Header bắt buộc `Idempotency-Key`: 1–128 ký tự ASCII `[A-Za-z0-9._:-]`, ví dụ UUID. Không chứa PII.
Không dùng correlation ID để thay key. Giữ cùng key/payload sau timeout hoặc mất response; không đổi key để thử lại.

```http
POST /api/v1/operations/cash-ins
Authorization: Bearer <operator access token>
Idempotency-Key: demo-cash-in-007-a
Content-Type: application/json

{"accountId":"cce779a8-e442-43a9-b2e4-600dd01ba35b","amount":"500000","currency":"VND"}
```

```json
{
  "id":"52f60592-410d-41ed-9e2b-ad5b2eb41030",
  "accountId":"cce779a8-e442-43a9-b2e4-600dd01ba35b",
  "journalId":"de5fa6c9-57e3-43f1-8903-9beb23f432ad",
  "amount":"500000",
  "currency":"VND",
  "status":"POSTED",
  "postedAt":"2026-10-28T03:00:00.123456Z"
}
```

Không trả current balance trong receipt vì nó thay đổi sau giao dịch khác; dùng balance API của BL-006.
Amount không dùng JSON number, float/double, ký hiệu mũ, leading zero, phần lẻ hoặc dấu cộng.

| HTTP/code | Điều kiện |
| --- | --- |
| 400 INVALID_REQUEST | Thiếu key, malformed JSON/UUID, amount là JSON number/object |
| 400 VALIDATION_FAILED | Key sai format/độ dài, amount invalid/null, currency khác VND, thiếu field |
| 401 UNAUTHENTICATED | Missing/invalid JWT |
| 403 ACCESS_DENIED | Không có OPERATOR |
| 404 ACCOUNT_NOT_FOUND | Account đích không tồn tại |
| 404 CASH_IN_NOT_FOUND | Receipt không tồn tại hoặc khác operator identity |
| 409 IDEMPOTENCY_CONFLICT | Cùng actor/key, khác account hoặc amount hợp lệ |
| 409 ACCOUNT_STATE_INVALID | Command mới khi account FROZEN/CLOSED |
| 422 LEDGER_NOT_PROVISIONED / LEDGER_LINK_INCONSISTENT | Chưa provision hoặc marker/link bất nhất |
| 422 LEDGER_BALANCE_LIMIT | Customer balance sau cộng vượt 99999999999999999999 |
| 500 INTERNAL_ERROR | DB/commit fault; không lộ SQL, actor, key hoặc payload |

Validation/auth chạy trước replay: payload malformed hoặc JWT đã mất role vẫn bị từ chối, không có ngoại lệ vì key cũ.
Service internal còn kiểm tra input với `IDEMPOTENCY_KEY_INVALID`/`CASH_IN_REQUEST_INVALID` (422).
Lỗi không được cache; khi sửa precondition có thể thử lại cùng key. Timeout không chứng minh rollback:
retry POST với cùng key để lấy receipt; nếu biết ID thì GET. Chưa có public IN_PROGRESS/FAILED status.

## Dữ liệu, khóa và recovery

V5 chỉ thêm `banking.cash_ins`, indexes/constraints/triggers; không sửa V1–V4 và không seed tiền.
Unique `(actor_issuer, actor_subject, idempotency_key)` cố định operation bởi bảng cash-in riêng; hash không bao gồm
JSON whitespace/thứ tự field/correlation/token. UUID được parse rồi canonical hóa trước hash.

Flow: validate/authorize → INSERT claim ON CONFLICT DO NOTHING → SELECT claim FOR UPDATE → compare hash/replay
→ lock bank account ACTIVE → resolve customer/clearing ledger → BL-006 bank→ledger ordered locks
→ insert/finalize journal → complete receipt với journal posted_at → commit. Không gọi network dưới DB locks.

Insert conflict chờ transaction đầu commit/rollback. Commit: waiter thấy receipt đã hoàn tất; rollback: waiter lấy key
và thực hiện lại. Không catch unique violation rồi query trong transaction PostgreSQL đã aborted.
Deferred constraint không cho commit pending claim; xác minh journal reference/kind/actor/timestamp và đúng hai
postings customer CREDIT/clearing DEBIT bằng receipt amount. Completed receipts append-only, unique journal ID.

Account lock phối hợp freeze/close/provision/posting. Claim không tồn tại ngoài transaction khi chưa thành công,
nên chưa tạo pending financial work cần mở rộng closure guard. BL-011/018 vẫn phải mở rộng khi thêm holds/pending.
Cash-clearing chung serialize các cash-in, chưa có cam kết throughput. Không chạy nhiều journals trong một command.

Nếu migration lỗi: giữ V1–V4, điều tra lỗi, sửa bằng migration forward mới theo trạng thái Flyway; không reset volume.
Nếu request timeout/crash trước commit: PostgreSQL rollback khi connection kết thúc; nếu đã commit nhưng mất response:
retry cùng actor/key/payload trả receipt cũ. Không xóa key để “sửa” giao dịch; reversal là tác vụ riêng.
DB owner vẫn có thể disable triggers; production role separation/audit tamper evidence chưa được cung cấp.

## Acceptance

CashInIT dùng JWT ký RSA/JWKS HTTP thật và PostgreSQL Testcontainers; không mock financial persistence.
Bao gồm exact double-entry/actor, GET privacy, token renewal, lost-response retry, replay sau freeze, changed-payload
conflict, 8 duplicates, concurrent different payloads, 8 distinct commands, actor namespace, auth/validation,
state/provisioning guards, max20digits/overflow, failure ở finalize/receipt/commit, raw SQL guards và close/freeze races.
Baseline tests vẫn chạy; [phiếu review](../reviews/BL-007.md) ghi kết quả thực.
