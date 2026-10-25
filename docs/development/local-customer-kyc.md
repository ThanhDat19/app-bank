# Thử Customer/KYC trên Swagger

Trước tiên làm theo [đăng nhập local](local-iam.md). Cần hai user khác nhau trong realm `bank-lab`:
`customer-review` có client role `bank-api → CUSTOMER`, `operator-review` có `bank-api → OPERATOR`.
Tài khoản `banklab-admin` chỉ dùng Admin Console realm master để tạo user/gán quyền.

## Chạy phiên BL-004

Sau khi checkout branch `feat/BL-004-customer-kyc` hoặc main đã merge BL-004:

```powershell
. .\scripts\Use-LocalEnvironment.ps1
docker compose --profile iam up -d --wait postgres keycloak
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd spring-boot:run
```

Flyway tự áp dụng V2 vào DB cấu hình trong `.env`; không cần tạo bảng hoặc insert thủ công.
Sau migration có hai bảng `banking.customers` và `banking.kyc_decisions`, ban đầu không có dữ liệu.
IntelliJ Database cần Refresh/Synchronize schema `banking` để thấy bảng mới. Không dùng nhầm schema public
hoặc DB khác tên với POSTGRES_DB. DB integration tests thuộc container riêng, không xuất hiện ở DB local.

## 1. CUSTOMER tạo hồ sơ

Mở http://localhost:8080/swagger-ui.html, Authorize bằng `customer-review`. Nếu đã Authorized đúng user
thì không bấm lại; gọi `/api/v1/iam/me` để kiểm tra role CUSTOMER.

Trong nhóm **Customer**, chọn **POST /api/v1/customers → Try it out**, body:

```json
{"displayName":"Customer Demo A"}
```

Bấm Execute. Xem **Server response**, không chọn các dòng 201/400/409 trong phần Responses vì chúng
là tài liệu. Mong đợi HTTP **201**, status **PENDING**, version **0**. Lưu `id` trả về để dùng ở bước sau.
`X-Correlation-ID` có thể để trống. GET `/api/v1/customers/me` trả hồ sơ vừa tạo.
Gọi POST tạo lần nữa nhận **409 CUSTOMER_ALREADY_EXISTS** là đúng, không phải lỗi môi trường.

## 2. OPERATOR xem và quyết định

Dùng cửa sổ riêng tư hoặc browser khác để có phiên Keycloak tách biệt. Mở Swagger → Authorize bằng
`operator-review`; `/iam/me` phải trả OPERATOR. Đừng gán quyền quản trị Keycloak cho tài khoản này.
Trong nhóm **Customer Operations**:

1. GET `/api/v1/operations/customers/{id}` với id đã lưu; kiểm tra PENDING/version 0.
2. POST `/api/v1/operations/customers/{id}/kyc-decisions` với body:

```json
{"expectedVersion":0,"decision":"VERIFIED","reason":"DOCUMENTS_MATCH"}
```

Mong đợi **200**, VERIFIED, version **1**. Quay lại phiên CUSTOMER, GET `/customers/me` thấy kết quả mới.
Customer không cần đăng nhập lại để thấy trạng thái vì KYC được đọc từ PostgreSQL, không lấy từ role JWT.

Nếu tài khoản có cả CUSTOMER và OPERATOR, hệ thống vẫn từ chối **422 KYC_SELF_REVIEW_FORBIDDEN** khi
duyệt chính hồ sơ mình. Dùng operator có identity khác. Role OPERATOR chỉ gán cho user thử nghiệm có nhiệm vụ review.

## 3. Các case để review

| Thao tác | Kết quả đúng |
| --- | --- |
| Bỏ authorization rồi gọi customer API | 401 |
| CUSTOMER gọi operations API | 403 |
| OPERATOR-only gọi POST customers | 403 |
| CUSTOMER B GET customer ID của A | 404 |
| POST thêm `kycStatus` hoặc `identitySubject` | 400 |
| Tạo trùng hồ sơ của cùng user | 409 CUSTOMER_ALREADY_EXISTS |
| Duyệt lại với expectedVersion 0 sau thành công | 409 CUSTOMER_VERSION_CONFLICT |
| Duyệt lại với version hiện tại 1 | 409 KYC_ALREADY_DECIDED |
| Hồ sơ PENDING, VERIFIED + INCOMPLETE | 422 KYC_REASON_INVALID |
| Hồ sơ PENDING mới, REJECTED + INCOMPLETE | 200 REJECTED/version 1 |

Để thử REJECTED cần một hồ sơ PENDING khác; không sửa DB để trả VERIFIED về PENDING.
Nếu POST timeout, GET profile trước khi retry. Không có API xóa/reset hồ sơ ở BL-004.
Hiện không có account, balance hay chuyển tiền. Không coi KYC mô phỏng là xác minh giấy tờ thật.

## 4. Xem dữ liệu

Trong IntelliJ/PostgreSQL, chỉ đọc để review:

```sql
SELECT id, display_name, kyc_status, version, created_at, updated_at
FROM banking.customers;

SELECT customer_id, from_status, to_status, reason, customer_version, decided_at
FROM banking.kyc_decisions;
```

Sau quyết định thành công có đúng một history row và customer version 1. Không sửa/xóa history bằng tay.
Không gửi token/password hoặc identity links trong ảnh/log review. Xem [đặc tả đầy đủ](../api/customer-kyc.md)
để đối chiếu rules và lỗi, cùng [bằng chứng kiểm thử](../reviews/BL-004.md).
