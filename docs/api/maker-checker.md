# BL-019 — Operations maker-checker

Issue [#41](https://github.com/ThanhDat19/app-bank/issues/41), dependency PR #40 merged; V16.
Một OPERATOR lập yêu cầu, OPERATOR có cặp issuer/subject khác quyết định. CUSTOMER không truy cập API này.
Áp dụng bắt buộc cho FREEZE, UNFREEZE và full REVERSAL; cash-in, KYC và external UNKNOWN resolution chưa thuộc phạm vi.

## Rules

| Rule | Contract / enforcement |
| --- | --- |
| OPS-01 | Verified OPERATOR trên mọi service method; identity từ JWT đã xác minh, không từ body/header tự khai |
| OPS-02 | Maker khác checker theo cả issuer/subject. Token mới của cùng identity vẫn không tự duyệt/từ chối |
| OPS-03 | Với account controls, cả maker và checker phải khác customer owner, kể cả dual-role |
| OPS-04 | Type/target/expected account version/reason/maker/key/hash bất biến; cùng maker/key khác payload409 |
| OPS-05 | Prepare không đổi trạng thái account, giữ tiền hoặc ghi journal. Replay trả trạng thái hiện tại trước revalidation |
| OPS-06 | PENDING→APPROVED/REJECTED/CANCELLED terminal, version0→1. EXECUTING chỉ tồn tại trong transaction chưa commit |
| OPS-07 | Approve khóa request trước account/original/ledger. Execution claim ràng buộc transaction ID, checker và đúng intent |
| OPS-08 | Existing account/reversal checks chạy lại tại execution; lỗi rollback approval và hiệu ứng, intent vẫn PENDING |
| OPS-09 | Same checker approve retry version0 hoặc1 trả nguyên receipt; checker khác409. Không thực thi lại sau terminal |
| OPS-10 | Maker-only cancel MAKER_WITHDREW; checker khác maker reject NOT_JUSTIFIED. Cùng actor/action retry trả nguyên receipt |
| OPS-11 | Decision + result + account/reversal + history + central audit commit cùng nhau; SQL cấm incomplete/fake approval |
| OPS-12 | Legacy direct mutation APIs trả409 OPERATION_APPROVAL_REQUIRED; application guard và SQL guard không cho bỏ qua workflow |

Không có expiry/scheduler/resubmit; pending intent có thể để lại và maker hủy. Chưa giữ trước tiền/hạn mức cho reversal.
Approval không bảo đảm precondition từ lúc prepare còn đúng: account có thể stale/frozen/closed hoặc tiền đã bị chi/giữ.
Reversal vẫn full principal, không hoàn phí/khôi phục daily usage, không hỗ trợ external settlement/ADJUSTMENT/reversal-of-reversal.

## API và ví dụ

Tất cả responses no-store, Problem Details/correlation theo conventions. Key1–128 ASCII `[A-Za-z0-9._:-]`,
case-sensitive; scope maker issuer/subject + operations namespace, dùng chung cho ba types. Không đưa PII vào key.
Hash SHA-256 của `type\ncanonical-target-UUID\nexpectedAccountVersion-or-null\nreason`; key được giữ vô thời hạn.

| Method/path dưới `/api/v1/operations/requests` | Body / kết quả |
| --- | --- |
| POST root + Idempotency-Key | Create body;201 + Location, PENDING/version0; replay trả state hiện tại |
| GET root `?status=PENDING&limit=50` | Latest bounded queue, createdAt DESC/id DESC; allowed PENDING/APPROVED/REJECTED/CANCELLED |
| GET `/{id}` |200, mọi OPERATOR được xem; missing404 |
| GET `/{id}/history?limit=50` |200, newest version trước; status/reason/time, không raw identity |
| POST `/{id}/approve` | `{"expectedVersion":0}`;200 APPROVED + durable result |
| POST `/{id}/reject` | `{"expectedVersion":0,"reason":"NOT_JUSTIFIED"}`;200 REJECTED |
| POST `/{id}/cancel` | `{"expectedVersion":0,"reason":"MAKER_WITHDREW"}`;200 CANCELLED |

Limit1–100; đây là bounded browse, chưa là full export hay queue worker. Không cho client truyền maker/checker/status/result/amount/postings;
unknown fields400. `expectedVersion` cho decision bắt buộc, khác `expectedAccountVersion` trong intent.

Maker tạo bằng JWT A và key mới:

```json
{"type":"FREEZE","targetId":"<bank account UUID>","expectedAccountVersion":0,"reason":"SECURITY_REVIEW"}
```

Chưa freeze. Lưu operation `id`, checker dùng JWT B POST `/{id}/approve` version0.
`result` là account metadata: id/accountNumber/currency/status/version/createdAt/updatedAt, không customer identity.
UNFREEZE dùng version account vừa đọc, reason REVIEW_CLEARED; FREEZE chỉ CUSTOMER_REQUEST/SECURITY_REVIEW.

Reversal maker:

```json
{"type":"REVERSAL","targetId":"<original posted journal UUID>","reason":"OPERATOR_ERROR"}
```

`expectedAccountVersion` phải absent/null; reason OPERATOR_ERROR hoặc DUPLICATE_PROCESSING.
Checker approve trả `result` là [full reversal receipt](reversal.md), amount exact integer string.
Reversal/journal actor là checker; `operation_history` và `audit_events` lưu cả maker và checker, link bằng operation ID.
Reversal internal key là operation UUID, không phải key client. Existing GET reversal receipt vẫn creator/checker-only;
maker và các OPERATOR khác đọc durable result qua operation request. Account result không đổi khi account thay đổi sau này.

| HTTP/code | Ý nghĩa |
| --- | --- |
|400 INVALID_REQUEST / VALIDATION_FAILED | Malformed/unknown fields, missing key/decision version, invalid UUID/type, negative versions/bounds |
|401 /403 | JWT không hợp lệ / thiếu OPERATOR |
|404 OPERATION_NOT_FOUND / ACCOUNT_NOT_FOUND / REVERSAL_ORIGINAL_NOT_FOUND | Request/target missing; reversal target recheck khi approve |
|409 IDEMPOTENCY_CONFLICT | Maker/key đã gắn intent khác |
|409 OPERATION_VERSION_CONFLICT | Decision expectedVersion khác version hiện tại; terminal same-actor replay chỉ version0/1 |
|409 OPERATION_ALREADY_DECIDED | Terminal conflict, approval của checker khác hoặc đổi quyết định |
|409 OPERATION_APPROVAL_REQUIRED | Gọi legacy direct API hoặc application entry point thiếu execution claim |
|422 OPERATION_SELF_APPROVAL_FORBIDDEN / OPERATION_NOT_MAKER | Tự duyệt/từ chối hoặc người khác hủy |
|422 ACCOUNT_SELF_OPERATION_FORBIDDEN | Maker/checker là owner tài khoản |
|422 OPERATION_REQUEST_INVALID / OPERATION_REASON_INVALID / OPERATION_STATUS_INVALID | Type/reason/version combination hoặc decision reason/filter không hợp lệ |
|409/422 domain financial codes | ACCOUNT_VERSION_CONFLICT, ACCOUNT_STATE_INVALID, REVERSAL_ALREADY_EXISTS, REVERSAL_NOT_SUPPORTED, LEDGER_INSUFFICIENT_FUNDS… |
|500 INTERNAL_ERROR | Unexpected execution/commit failure, không lộ SQL/identity/key; transaction rollback |

## Recovery và cutover

Sau timeout create: giữ nguyên maker/key/body, retry để tìm request. Sau timeout approve: GET operation và retry
bằng đúng checker với version0; receipt đã APPROVED được trả lại nguyên trạng, không ghi lần hai. PENDING có thể retry
sau khi precondition được phục hồi; intent sai/stale không được sửa, maker hủy rồi tạo yêu cầu mới có key mới.
Không dùng key cũ cho một original khác dù approve trước thất bại: intent vẫn được giữ. Check database state sau500/timeout
trước thao tác khác; không suy diễn response mất là transaction thất bại.

V16 forward-only, không sửa migrations cũ hoặc historical actors. Thêm nullable operation link vào history/reversals;
legacy rows giữ nguyên nội dung nghiệp vụ, không dựng approval giả. Existing GET receipts tiếp tục dùng được.
POST legacy freeze/unfreeze/reversals không còn dùng để mutation/replay; client chuyển sang requests API.
Không rollback app về bản cũ rồi tắt triggers để tiếp tục direct operations. Triển khai app + schema/client cutover có phối hợp;
app cũ sẽ bị SQL guard từ chối an toàn. Không tự migrate `bank_lab` trong tác vụ này.

Audit operation tự sinh từ history, xác minh actor/action/time và immutable; xem `/operations/audit-events` hoặc
history endpoint. Không log raw JWT/key/identity; authenticated OPERATOR có quyền xem queue/result theo policy lab.
Database superuser/owner có thể sửa quyền/tắt trigger; đây không là audit chống DBA hay bằng chứng hai con người độc lập.
IAM admin phải cấp hai danh tính đúng người; chưa MFA/step-up, operator team scoping, retention, forced UNKNOWN resolution hay production authorization certification.
