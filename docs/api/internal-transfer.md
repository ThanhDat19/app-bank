# BL-008 — Chuyển tiền nội bộ an toàn

Base main `7f467e8`: owner đã merge BL-007/PR #19. BL-008 dùng posted balance của Ledger và cash-in mô phỏng
để chuẩn bị tiền thử. Policy bên dưới là phạm vi triển khai để owner review trước merge.

## Actors và rules

| Rule | Contract |
| --- | --- |
| TRF-01 | CUSTOMER có JWT đã verify và sở hữu source account. OPERATOR-only không chuyển thay customer. |
| TRF-02 | Source/destination là hai UUID bank account khác nhau, cùng VND, ACTIVE, đã provision Ledger. |
| TRF-03 | Amount JSON string canonical nguyên dương, tối đa 20 chữ số; không fee/rounding/multi-currency trong MVP. |
| TRF-04 | Một journal: DEBIT source deposit LIABILITY, CREDIT destination deposit LIABILITY, cùng amount. Tổng posted money bảo toàn. |
| TRF-05 | Khóa toàn bộ bank-account pair ORDER BY id ở PostgreSQL, rồi ledger accounts theo thứ tự BL-006. Không khóa source trước pair. |
| TRF-06 | Kiểm tra cuối ownership/state/provisioning/balance dưới locks; không âm source, không vượt max destination. |
| TRF-07 | Key scope = verified issuer + subject + transfer operation; SHA-256 canonical source/destination/currency/amount. |
| TRF-08 | Claim, debit, credit, receipt commit cùng READ COMMITTED transaction; không commit pending claim. |
| TRF-09 | Same actor/key/payload trả nguyên receipt 201; changed valid payload 409. Retry không debit/credit thêm. |
| TRF-10 | GET receipt chỉ sender identity đã tạo; recipient/other identity/missing cùng 404. |
| TRF-11 | Receipt/key giữ vô thời hạn, không update/delete/truncate sau hoàn tất; business/DB/commit error rollback cả key và tiền. |

Recipient không cần cùng owner với source. Client phải biết destination UUID từ dữ liệu mô phỏng; BL-008 không có
recipient directory, tra cứu tên/PII, chuyển bằng số tài khoản hoặc operations lookup. Receipt chỉ là trạng thái
command đã POSTED, không phải statement hay current/available balance. Recipient xem own balance qua BL-006.

## REST

JWT, Problem Details, correlation và `Cache-Control: no-store` theo conventions hiện có.

| Method/path | Kết quả |
| --- | --- |
| POST `/api/v1/transfers` | 201 POSTED receipt + Location; cùng status/body/Location khi replay |
| GET `/api/v1/transfers/{id}` | 200 original receipt của sender identity hiện tại |

Header bắt buộc `Idempotency-Key`: 1–128 ASCII `[A-Za-z0-9._:-]`, case-sensitive, ví dụ UUID.
Không chứa PII; correlation ID không thay idempotency key. Key của cash-in và transfer là hai namespace riêng.
Đổi key hoặc actor là một command mới; không dedupe ý định giữa các identity.

```http
POST /api/v1/transfers
Authorization: Bearer <source owner CUSTOMER access token>
Idempotency-Key: demo-transfer-008-a
Content-Type: application/json

{"sourceAccountId":"a06bb3a9-0ca7-4c82-a3fa-1a0238fdab11","destinationAccountId":"b795c6b1-39f6-4c28-96aa-a2316cd1d322","amount":"100000","currency":"VND"}
```

```json
{
  "id":"418aa349-7617-4dcf-a9f1-e67f6ea6d308",
  "sourceAccountId":"a06bb3a9-0ca7-4c82-a3fa-1a0238fdab11",
  "destinationAccountId":"b795c6b1-39f6-4c28-96aa-a2316cd1d322",
  "journalId":"0aef429a-b096-4cf1-83b1-6e5405eb5bd1",
  "amount":"100000",
  "currency":"VND",
  "status":"POSTED",
  "postedAt":"2026-10-28T10:00:00.123456Z"
}
```

Không dùng JSON number, dấu cộng, leading zero, phần lẻ hoặc exponent cho amount. Không nhận actor, journal ID,
ledger IDs, posting sides, status hoặc balances từ client. UUID parse rồi canonical hóa; JSON field order,
whitespace, UUID letter case, token renewal và correlation không đổi hash.

Hash input UTF-8: `transfer:v1\n<canonical source UUID>\n<canonical destination UUID>\nVND\n<canonical amount>`.

| HTTP/code | Điều kiện |
| --- | --- |
| 400 INVALID_REQUEST | Thiếu key, malformed JSON/UUID, amount là JSON number/object |
| 400 VALIDATION_FAILED | Thiếu/null field, invalid amount/key format/length, currency khác VND |
| 401 UNAUTHENTICATED | Missing/invalid JWT |
| 403 ACCESS_DENIED | Thiếu CUSTOMER, kể cả OPERATOR-only |
| 404 ACCOUNT_NOT_FOUND | Source missing/not owned, hoặc destination missing sau source authorization |
| 404 TRANSFER_NOT_FOUND | Receipt missing hoặc không thuộc sender identity |
| 409 IDEMPOTENCY_CONFLICT | Cùng actor/key, thay source/destination/amount hợp lệ |
| 409 ACCOUNT_STATE_INVALID | Command mới khi một trong hai account FROZEN/CLOSED |
| 422 TRANSFER_ACCOUNTS_INVALID | Source bằng destination |
| 422 LEDGER_NOT_PROVISIONED / LEDGER_LINK_INCONSISTENT | Một trong hai account chưa provision hoặc marker/link sai |
| 422 LEDGER_INSUFFICIENT_FUNDS | Posted source balance sau debit âm |
| 422 LEDGER_BALANCE_LIMIT | Destination posted balance vượt 99999999999999999999 |
| 500 INTERNAL_ERROR | Unexpected DB/commit failure; không lộ SQL, amounts, identity hoặc key |

Direct internal service validation còn có IDEMPOTENCY_KEY_INVALID/TRANSFER_REQUEST_INVALID (422).
Payload validation và JWT/role vẫn chạy trước replay. Receipt đã commit replay được kể cả source đã CLOSED,
destination FROZEN, hoặc current balance không còn đủ; replay không thực hiện journal mới.
Thất bại không được cache: có thể nạp thêm tiền/sửa precondition rồi retry đúng key/payload.

## Transaction, locking và recovery

V6 chỉ thêm `banking.transfers` và guards; không sửa V1–V5 hoặc seed tiền. Unique actor/key và unique journal ID;
source/destination FKs deferred cho phép application trả đúng 404 trước commit, thay vì lộ DB error.

Flow: validate/authorize → INSERT claim ON CONFLICT DO NOTHING → SELECT claim FOR UPDATE → compare hash/replay
→ source ownership precheck **không lock** → lock source+destination ORDER BY id → recheck ownership/state
→ resolve provisioned ledger links → BL-006 locks/balance checks → post two lines/finalize → complete receipt → commit.

Source-first lock có thể khiến A→B giữ A chờ B, B→A giữ B chờ A. Vì vậy ownership precheck không lấy row lock;
pair được khóa theo cùng PostgreSQL UUID order cho cả hai chiều, rồi ownership được kiểm tra lại từ rows đã khóa.
Không dùng Java UUID sort để thay PostgreSQL order. Không gọi network dưới financial locks.

Claim conflict chờ transaction đầu commit/rollback. Commit thì waiter đọc receipt cũ; rollback thì waiter có thể
claim và chạy lại. Không catch unique violation rồi tiếp tục query trong transaction PostgreSQL đã aborted.
Deferred trigger không cho pending claim commit; kiểm tra journal/reference/kind/actor/time, source owner từ Customer,
đúng hai lines và amount/direction/customer linkage. POSTED ledger và completed receipt đều bất biến.

Account locks phối hợp transfer/cash-in/freeze/close/provisioning. Receipt không để pending command sống ngoài transaction,
nên không thêm pending work cần closure guard mới. BL-011/018 vẫn phải mở rộng guard trước holds/pending APIs.
Một command chỉ có một journal. Nếu một outer transaction gọi nhiều financial commands cần coordinator pre-lock union;
BL-008 không cung cấp batch transfer. Chưa có throughput/SLO claim.

Timeout không chứng minh rollback. Retry POST cùng actor/key/payload để lấy receipt; nếu biết ID thì GET.
Chưa có public PENDING/FAILED status, key lookup endpoint, expiry hoặc cleanup. Không đổi key để chữa lost response.
Không sửa/xóa receipt/postings để đảo tiền; reversal là BL-010. Migration recovery forward-only; không reset volume.
DB owner vẫn có thể disable triggers; production role separation/audit tamper evidence chưa thuộc scope.

## Acceptance evidence

TransferIT dùng signed RSA/JWKS HTTP thật, cash-in API để funding và PostgreSQL Testcontainers. Kiểm tra auth/ownership,
private receipt, exact debit/credit, retries/conflicts, overspend, opposite-direction locks, incoming credits, max/overflow,
state/provisioning, faults finalize/receipt/commit và freeze/close races. Các case giữ row lock và quan sát pg_stat_activity
chứng minh request thực sự chờ khóa, rồi commit balance/state/provisioning mới để kiểm tra final validation.
SQL fixtures cho contention không được dùng làm bằng chứng JWT authorization.

TransferMigrationIT độc lập service/HTTP: V5 có cash-in/posted money → V6 giữ nguyên; raw SQL pending/misrepresented
receipts bị chặn và rollback journal; correct receipt commit rồi không sửa/xóa/truncate. Baseline tests vẫn chạy.
Xem [phiếu review](../reviews/BL-008.md) và [Swagger guide](../development/local-internal-transfer.md).
