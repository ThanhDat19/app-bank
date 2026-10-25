# Đăng nhập local qua Keycloak và Swagger

## 1. Khởi động

Giữ nguyên `.env` đang dùng, bổ sung `KEYCLOAK_ADMIN_PASSWORD` với mật khẩu local riêng.
Nếu mới clone thì copy `.env.example` và thay cả hai password mẫu. Không copy đè `.env` hiện có.

```powershell
. .\scripts\Use-LocalEnvironment.ps1
docker compose --profile iam up -d --wait postgres keycloak
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd spring-boot:run
```

Core http://localhost:8080, Keycloak http://localhost:8180. Giữ hostname `localhost` khi dùng Swagger
để khớp redirect URI/web origin trong client. PostgreSQL và Keycloak chỉ publish cổng loopback.
Keycloak bootstrap admin là `banklab-admin`, password lấy từ `.env`. Spring không đọc `.env` trực tiếp.
Các biến POSTGRES cũ vẫn dùng được khi chỉ chạy core; muốn đăng nhập cần bổ sung password admin.
Ngoài profile local phải cấp `OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI`, `OIDC_AUDIENCE` và endpoint HTTPS.

## 2. Tạo user để tự review

1. Mở http://localhost:8180/admin/, đăng nhập bootstrap admin; chuyển từ realm `master` sang `bank-lab`.
2. Users → Add user: username tổng hợp (ví dụ `customer-review`), Enabled; nhập thông tin thử nghiệm nếu
   user profile yêu cầu. Không sử dụng thông tin cá nhân thật.
3. Credentials → Set password, đặt password local; tắt Temporary cho user demo nếu không muốn bước đổi mật khẩu.
4. Role mapping → Assign role → Filter by clients → chọn `bank-api` / `CUSTOMER`.
5. Tạo user `operator-review` riêng, gán client role `bank-api` / `OPERATOR` để so sánh.

Bootstrap admin của realm master dùng để quản trị, không dùng làm khách hàng đăng nhập realm bank-lab.
Realm import không tạo sẵn user/password. User review được lưu ở volume Keycloak; script dưới đây chỉ
tạo user riêng dùng tạm rồi xóa, nên sau khi chạy script bạn vẫn cần user do mình tạo để thử Swagger.

## 3. Thử API trong Swagger

Mở http://localhost:8080/swagger-ui.html → Authorize → `oidc` với client `bank-swagger`, scope `openid`.
Không điền client secret: đây là public client dùng PKCE. Đăng nhập bằng user CUSTOMER vừa tạo.
Thử `GET /api/v1/iam/me`: HTTP 200 trả issuer, subject và `["CUSTOMER"]`.

Chưa có Customer/Account thì kết quả này vẫn hợp lệ: endpoint đọc danh tính đã xác minh từ token,
không truy vấn hồ sơ ngân hàng. PostgreSQL chỉ có `banking` và `public.flyway_schema_history` ở phiên này.
Không chèn thủ công balance hoặc customer để làm login. BL-004 sẽ thêm bảng và API Customer/KYC.
Không có bearer token → 401. Có token nhưng chưa gán đúng client role → 403.

Để đổi tài khoản, logout/clear authorization trong Swagger và đăng xuất phiên realm Keycloak trước khi
Authorize lại, hoặc dùng cửa sổ riêng tư. Xóa authorization ở Swagger không thu hồi access token đã phát.
Offline JWT còn có thể được chấp nhận đến khi hết hạn cộng clock skew; đổi role cần lấy token mới.

## 4. Kiểm chứng tự động với IdP thật

Trong terminal thứ hai, cần Python 3 (không thêm package):

```powershell
. .\scripts\Use-LocalEnvironment.ps1
python .\scripts\verify-local-oidc.py
```

Script chỉ gọi localhost:8080/8180, không dùng proxy môi trường; tạo user tổng hợp có tên ngẫu nhiên,
gán rồi bỏ từng role, chạy Authorization Code + PKCE, kiểm tra `/me`, 403/404 URL gate, ID token bị 401,
thiếu PKCE/wrong verifier bị từ chối và password grant của `bank-swagger` bị tắt. Script xóa user trong
`finally`; đầu ra chỉ PASS hoặc lỗi đã lọc, không in token/password/code. Cần mật khẩu admin để setup
và cleanup fixture; password grant của `admin-cli` realm master chỉ dùng quản trị local, khác client ứng dụng.
Nếu process bị kill trước cleanup, tìm và xóa đúng user `bl003-verify-*` vừa tạo, giữ nguyên các user khác.

## 5. Khi có lỗi

- Core không khởi động: kiểm tra DB_URL, POSTGRES_DB và DB đang tồn tại. Thay POSTGRES_DB trong `.env`
  không tạo database mới trong volume PostgreSQL cũ. Dùng tên DB hiện hữu hoặc chủ động tạo DB mới;
  không xóa volume để chữa cấu hình. Health UP không có nghĩa đã có bảng nghiệp vụ.
- 401: kiểm tra issuer localhost, audience `bank-api`, access token mới và thời gian máy. Không gửi ID token.
- 403: kiểm tra **client role** của `bank-api`, không chỉ realm role; lấy token mới sau khi gán.
- Keycloak chạy nhưng realm chưa có: kiểm tra Docker logs và realm import mount.
- Đã có realm từ bản cấu hình trước: `--import-realm` bỏ qua realm hiện hữu. Không restart rồi giả định
  JSON được áp dụng lại. Trong Clients → bank-swagger → Client scopes, kiểm tra `basic` ở dạng Default;
  scope này phải có mapper `sub` cho access token. Bản JSON hiện tại đã đặt `defaultClientScopes: ["basic"]`.
  Không xóa realm/volume có user chỉ để thêm scope. Các thay đổi khác cũng cần áp dụng có chủ đích qua Admin Console.
- Đổi KEYCLOAK_ADMIN_PASSWORD trong `.env` không đổi password admin đã được bootstrap trong volume cũ;
  cập nhật credentials qua Admin Console bằng tài khoản hiện hữu.
- Swagger redirect/CORS lỗi: dùng đúng localhost:8080; cổng/hostname khác cần đổi redirect URI và web origin
  của client cùng với config Swagger. Không mở wildcard redirect để bỏ qua lỗi.

`docker compose --profile iam stop keycloak postgres` dừng dịch vụ và giữ volume. H2/start-dev chỉ là
môi trường phát triển; xem [ADR-003](../architecture/ADR-003-identity-boundary.md) về phạm vi bảo mật.
