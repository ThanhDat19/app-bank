# BL-005 — Account lifecycle

Issue [#14](https://github.com/ThanhDat19/app-bank/issues/14). Tài khoản thanh toán VND mô phỏng,
trước khi xây Ledger. Đây là metadata/state API; không có balance, cash-in, transfer hoặc ghi tiền.
Policy trong PR cần owner review; chưa phải chính sách sản phẩm/ngân hàng thật.

**Cập nhật BL-006:** Ledger/closure adapter đã được triển khai. Sau provision, account balance 0 có thể
close; nonzero bị chặn. Phần dưới giữ mô tả baseline BL-005; xem [Ledger](ledger.md) cho behavior hiện tại.

## Business rules

| ID | Quy tắc | Bảo vệ |
| --- | --- | --- |
| ACC-01 | CUSTOMER phải có Customer KYC VERIFIED mới được mở | CustomerAccountAccess khóa row Customer và đọc DB trong transaction mở account |
| ACC-02 | Một account/customer/currency, kể cả CLOSED; MVP chỉ VND | Unique DB, ON CONFLICT; không xóa/tạo lại để mở lại |
| ACC-03 | UUID là resource ID; số account 12 chữ số do sequence cấp | PK, unique/check số account; không nhận số account từ request |
| ACC-04 | Owner được xác định qua issuer/subject và Customer application contract | Account module không đọc trực tiếp bảng hoặc domain nội bộ Customer |
| ACC-05 | CUSTOMER chỉ đọc/đóng account mình; missing và ngoài ownership đều 404 | Query id + customerId; CUSTOMER gate ở application |
| ACC-06 | OPERATOR xem/freeze/unfreeze; không đổi trạng thái account mình | URL + method security và kiểm tra identity owner qua Customer contract |
| ACC-07 | ACTIVE→FROZEN, FROZEN→ACTIVE, ACTIVE→CLOSED; CLOSED terminal | Pure domain + DB history CHECK |
| ACC-08 | Gửi expectedVersion, mỗi thay đổi tăng một version | FOR UPDATE và conditional UPDATE WHERE id/version/status |
| ACC-09 | OPEN và mọi state change có history cùng transaction | Account + history rollback cùng nhau khi lỗi |
| ACC-10 | Chỉ đóng khi financial check CLEAR; thiếu thông tin thì từ chối | AccountClosureGuard, domain rule và row lock |

## State matrix và quyền

| Action | Actor | Trước → sau | Reason cho phép |
| --- | --- | --- | --- |
| Open | CUSTOMER, KYC VERIFIED | Chưa có → ACTIVE/version 0 | Server ghi OPENED |
| Freeze | OPERATOR khác owner | ACTIVE → FROZEN | CUSTOMER_REQUEST hoặc SECURITY_REVIEW |
| Unfreeze | OPERATOR khác owner | FROZEN → ACTIVE | REVIEW_CLEARED |
| Close | CUSTOMER owner | ACTIVE → CLOSED | CUSTOMER_REQUEST, financial check CLEAR |

FROZEN không được đóng để tránh khách hàng bỏ qua lệnh đóng băng của operations.
Mọi action khác là ACCOUNT_STATE_INVALID; CLOSED vẫn đọc được, không reopen và không cấp account VND mới
cho cùng Customer trong MVP. Future debit **và credit** đều phải từ chối FROZEN/CLOSED dưới cùng account lock.
Policy credit này là lựa chọn mô phỏng, không suy diễn quy tắc cho mọi ngân hàng.

## Ranh giới tài chính trước Ledger

`accounts` không lưu số dư. `ledger_provisioned=false` chỉ có nghĩa chưa tạo liên kết sổ cái, và hiện chưa
có API ghi tiền/hold/pending payment. Trong trạng thái này account chỉ là metadata và có thể đóng.
Nếu marker true, adapter hiện trả **UNAVAILABLE**, không coi Ledger/hold thiếu dữ liệu là số 0.
Kết quả HTTP là 422 ACCOUNT_CLOSURE_UNAVAILABLE, account/history không đổi.

Marker do server/DB giữ, không có trong request/response. Client không thể đặt nó false để bỏ qua guard.
**Trước khi có bất kỳ ghi tiền nào ở BL-006/007**, provisioning phải:

1. Khóa account row, kiểm tra không CLOSED và state cho phép theo action.
2. Tạo ledger linkage và đặt marker true trong cùng transaction, không được reset marker.
3. Thay closure guard bằng adapter đọc authoritative posted balance, holds và pending payments trong cùng
   transaction/lock order. Chỉ CLEAR nếu tất cả được kiểm chứng; missing/error → UNAVAILABLE.
4. Test provisioning-vs-close và posting/hold-vs-close, không chỉ unit test boolean.

Port có CLEAR, NON_ZERO_BALANCE, ACTIVE_HOLDS, PENDING_PAYMENTS, UNAVAILABLE; domain chỉ cho CLEAR.
BL-005 đã kiểm thử domain từ chối các kết quả chặn, nhưng **chưa có số dư/hold thực** để integration-test
NON_ZERO_BALANCE/ACTIVE_HOLDS/PENDING_PAYMENTS. Không tuyên bố đây là kiểm tra đóng tài khoản có tiền hoàn chỉnh.

## REST contracts

Bearer JWT như BL-003; responses no-store và X-Correlation-ID. Error dùng [ApiProblem](conventions.md).

| Method/path | Role | Thành công |
| --- | --- | --- |
| POST `/api/v1/accounts` | CUSTOMER | 201 + Location, ACTIVE/version 0 |
| GET `/api/v1/accounts/me` | CUSTOMER | 200 array, hiện tối đa một account; chưa có Customer/account thì `[]` |
| GET `/api/v1/accounts/{id}` | CUSTOMER owner | 200 |
| POST `/api/v1/accounts/{id}/close` | CUSTOMER owner | 200 CLOSED/version +1 |
| GET `/api/v1/operations/accounts/{id}` | OPERATOR | 200 |
| POST `/api/v1/operations/accounts/{id}/freeze` | OPERATOR khác owner | 200 FROZEN/version +1 |
| POST `/api/v1/operations/accounts/{id}/unfreeze` | OPERATOR khác owner | 200 ACTIVE/version +1 |

Open body:

```json
{"currency":"VND"}
```

Không nhận customerId, status, balance, ledgerProvisioned hoặc accountNumber. Unknown fields bị 400.
GET `/accounts/me` là array có giới hạn tự nhiên bởi policy một currency, chưa cần pagination; nếu mở rộng
nhiều account/currency phải sửa contract và thiết kế pagination trước.

Response ví dụ (sequence có thể có gaps khi conflict/rollback, không phải counter nghiệp vụ):

```json
{
  "id":"cce779a8-e442-43a9-b2e4-600dd01ba35b",
  "accountNumber":"100000000000",
  "currency":"VND",
  "status":"ACTIVE",
  "version":0,
  "createdAt":"2026-10-26T02:00:00.123456Z",
  "updatedAt":"2026-10-26T02:00:00.123456Z"
}
```

Freeze body:

```json
{"expectedVersion":0,"reason":"SECURITY_REVIEW"}
```

Sau freeze version 1, unfreeze:

```json
{"expectedVersion":1,"reason":"REVIEW_CLEARED"}
```

Sau unfreeze version 2, owner close:

```json
{"expectedVersion":2,"reason":"CUSTOMER_REQUEST"}
```

| HTTP/code | Điều kiện |
| --- | --- |
| 400 INVALID_REQUEST / VALIDATION_FAILED | Unknown fields, enum/UUID sai, version âm/thiếu, currency khác VND |
| 401 UNAUTHENTICATED | Thiếu/sai token |
| 403 ACCESS_DENIED | Không có role của action; OPERATOR-only không mở/đóng thay khách hàng |
| 404 CUSTOMER_NOT_FOUND | Open nhưng chưa có profile |
| 404 ACCOUNT_NOT_FOUND | Không có account trong phạm vi cho phép |
| 409 ACCOUNT_ALREADY_EXISTS | Unique customer/currency, kể cả account đã CLOSED |
| 409 ACCOUNT_VERSION_CONFLICT | expectedVersion cũ hoặc request concurrent khác đã thắng |
| 409 ACCOUNT_STATE_INVALID | Action không phù hợp state, kể cả close FROZEN/CLOSED |
| 422 KYC_NOT_VERIFIED | Open khi PENDING hoặc REJECTED |
| 422 ACCOUNT_REASON_INVALID | Reason không thuộc action |
| 422 ACCOUNT_SELF_OPERATION_FORBIDDEN | OPERATOR đổi state account của chính mình |
| 422 ACCOUNT_CLOSURE_UNAVAILABLE | Account đã provision Ledger nhưng chưa có authoritative closure adapter |
| 422 ACCOUNT_BALANCE_NOT_ZERO / ACCOUNT_HAS_HOLDS / ACCOUNT_HAS_PENDING_PAYMENTS | Reserved mapping cho closure guard tương lai; BL-005 chưa phát sinh từ adapter thực |

Không có Idempotency-Key ở phiên metadata này. Retry open có thể 409; GET `/accounts/me` để khôi phục
kết quả. Timeout state command có thể đã commit; GET account để đọc state/version trước khi quyết định lại.
Không retry mù hoặc nâng expectedVersion tự động để ghi đè một lệnh khác.

## Schema và locking

V3 thêm sequence, `banking.accounts` (FK Customer, unique account number và customer/currency),
`banking.account_state_changes` (FK account, unique account/version, actor/time/reason và valid transition CHECK).
SQL CHECK dùng COALESCE(..., FALSE), tránh UNKNOWN do from_status NULL làm lọt history không hợp lệ.
History mở có from NULL, reason OPENED, version 0; các thay đổi sau có version >0.

Open: lock Customer → insert account → history. Mutations: resolve owner (read only nếu cần) → lock account →
state/version/guard checks → conditional update → history → commit. Không gọi mạng trong lock.
Hai lệnh freeze/close expectedVersion 0 không thể cùng commit: người thắng version 1, người sau 409.
Foreign-key/index phục vụ lookup; không có public list hoặc API sửa owner/currency/account number/marker.

V1/V2 giữ nguyên; tests nâng từ V2 và giữ Customer có sẵn. Không xóa volume để sửa migration.
History chưa có DB grants/append-only trigger/tamper evidence; đây chưa là audit hoàn chỉnh chống DBA sửa.

## Test mapping và cải thiện

AccountTest kiểm tra đủ 9 cặp state/action, stale version, reason và mọi closure check chặn/null.
AccountIT kiểm tra JWT/HTTP thật + PostgreSQL: KYC gate, ownership, self-operation, 8 concurrent opens,
8 concurrent freezes, freeze-vs-close, rollback khi history fail ở open và change, migration V2→V3,
SQL CHECK/NULL và Swagger. Đọc [phiếu review](../reviews/BL-005.md) để xem kết quả chạy.
PackagedApplicationIT còn đối chiếu mọi production source với class trong executable JAR, kiểm tra V3
và chặn test-only SecurityProbe. Test từ target/classes không thay thế kiểm chứng artifact được giao.

Điểm thiết kế đã cải thiện: không truyền Customer domain sang Account; không lưu balance giả để cho close;
không tin check/version trước lock; không ghi history sau transaction; không hiểu SQL CHECK NULL là false.
Nợ kế tiếp là Ledger/closure adapter và financial locking integration, không được quên marker khi bật ghi tiền.
