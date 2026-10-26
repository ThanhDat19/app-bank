# Kế hoạch phát triển và phân công

Ngày lập: 25/10/2026. Lịch dự kiến 26/10/2026–14/03/2027, 20 sprint một tuần.
BL-001 có thể bắt đầu chuẩn bị trước lịch. Giả định owner dành 10–15 giờ/tuần và review trong 1–2 ngày
làm việc; đây là ngân sách thời gian, không phải cam kết mọi tính năng vừa một tuần.
Re-estimate sau hai sprint đầu và mỗi mốc nghiệm thu. Nếu thiếu công suất, dời hạng mục nâng cao.

## Nhịp mỗi sprint

- Phiên A (2–3 giờ): BA đặc tả use case, rules, state/data/API, acceptance tests; owner chốt các lựa chọn.
- Phiên B (5–7 giờ): code + migration + tests + Swagger, tối đa một tác vụ nghiệp vụ đang triển khai.
- Phiên C (3–5 giờ): chạy checks, push PR, owner review từng commit, sửa bằng commit mới.

Một phiên tạo một đầu ra kiểm chứng được. Chức năng lớn chia nhiều PR theo dependency; không cần cố
giữ số commit bằng một con số nếu diff mất khả năng review. Đầu việc chưa merge không được tính Done.

## Sprint và tác vụ

| Sprint / ngày | Tác vụ chính | Phụ thuộc | Kết quả và tiêu chí review |
| --- | --- | --- | --- |
| S00: 26/10–01/11 | BL-001 Bootstrap | Repo/Issue #1 có sẵn | Wrapper, Boot, PG, Flyway, Swagger, CI; `verify` và runtime checks đạt |
| S01: 02–08/11 | BL-002 API conventions & module boundaries | BL-001 merge | Problem Details + business codes, correlation ID, package rules; malformed request/error contract tests |
| S02: 09–15/11 | BL-003 IAM baseline | BL-002 | OIDC/resource server, issuer/audience, roles, actor context; 401/403 và token negative tests |
| S03: 16–22/11 | BL-004 Customer & KYC giả lập | BL-003 | Tạo/xem khách hàng, KYC transitions, ownership, unique constraints; không public PII |
| S04: 23–29/11 | BL-005 Account lifecycle | BL-004 | Mở/freeze/unfreeze/close, state matrix, ownership; chỉ close account chưa provision Ledger, đã provision thì fail closed |
| S05: 30/11–06/12 | BL-006 Ledger foundation | BL-005 | Chart of accounts, journal/postings, debit/credit, DB balance guard, append-only; provisioning + closure adapter và race tests trước ghi tiền |
| S06: 07–13/12 | BL-007 Idempotent cash-in mô phỏng | BL-006 | Đối ứng rõ, quyền operator, request hash/unique key, retry không nạp trùng, rollback nguyên tử |
| S07: 14–20/12 | BL-008 Internal transfer an toàn | BL-007 | Ordered locks, kiểm tra cuối sau khóa, idempotency cùng transaction, trạng thái/lookup; không overspend |
| S08: 21–27/12 | BL-009 Concurrency + BL-010 Reversal | BL-008 | Stress tranh chấp, lost response/retry, immutable ledger, full reversal policy và duplicate reversal tests |
| S09: 28/12–03/01 | BL-011 Holds, limits, fees | BL-010 | Place/capture/release/expiry, available balance; limit atomic theo timezone; fee postings cân bằng |
| S10: 04–10/01 | BL-012 Transactional Outbox | BL-011 | Event commit cùng journal, eventId/aggregateVersion/schemaVersion; relay crash/retry tests |
| S11: 11–17/01 | BL-013 Kafka & Notification | BL-012 | Consumer dedupe, retry/backoff/DLQ, xử lý ordering; event không mất khi broker gián đoạn |
| S12: 18–24/01 | BL-014 Reconciliation & Audit | BL-013 | Job so ledger/projection/outbox, báo cáo sai lệch; audit actor/action/time; phát hiện sai dữ liệu giả lập |
| S13: 25–31/01 | BL-015 History/Statement + BL-016 SSE | BL-014 | Pagination ổn định, timezone/cut-off, statement; SSE authorize, reconnect + REST status resync |
| S14: 01–07/02 | BL-017 Risk service gRPC unary | BL-015 | Rule mô phỏng trước approval, deadline/failure policy; quyết định trễ không làm ghi tiền hai lần |
| S15: 08–14/02 | BL-018 External payment simulator | BL-011, BL-014, BL-017 | State machine, UNKNOWN, holds, query/reconcile; đối tác giả trả chậm/trùng/reordered |
| S16: 15–21/02 | BL-019 Operations maker-checker | BL-018 | Tách maker/checker, authorize thao tác freeze/reversal, chống tự duyệt, audit đầy đủ |
| S17: 22–28/02 | BL-020 Monitoring & performance | BL-019 | Metrics/tracing đã redaction, đo p95/p99/throughput/error; owner chốt workload và SLO sau baseline |
| S18: 01–07/03 | BL-021 Recovery + BL-022 Streams tùy công suất | BL-020 | Restore drill, failure injection; Kafka Streams cửa sổ thời gian nếu đủ thời gian |
| S19: 08–14/03 | BL-023 Portfolio release | Các mốc bắt buộc đạt | Demo, diagrams, threat model, báo cáo giới hạn, release notes và owner nghiệm thu v1.0 |

Ngày cuối năm ở S09 là 28/12/2026–03/01/2027; từ S10 dùng năm 2027.
BL mã nội bộ không đồng nhất GitHub issue number: hiện BL-001 = Issue #1, các issue sau tạo khi chuẩn bị
sprint tương ứng và phải dùng ID GitHub thực trong PR. Không tạo trước hàng loạt issue rỗng.

## Các mốc chặn phát triển phụ thuộc

| Mốc | Bằng chứng bắt buộc | Quyết định owner |
| --- | --- | --- |
| G0 sau BL-001 | Clean verify, PostgreSQL healthy, health/Swagger/API, CI và từng commit | Approve/Request changes, merge nền tảng |
| G1 trước ghi tiền | IAM/ownership, account state rules, journal cân bằng/immutable | Chấp thuận contract cash-in |
| G2 sau S08 | N request cạnh tranh không âm tiền, duplicate/retry không ghi trùng, rollback và reversal đúng | Cho mở rộng holds/events |
| G3 sau S12 | Broker down/relay crash recover, dedupe, đối soát phát hiện lệch | Cho mở rộng external workflow |
| G4 release | E2E, restore, threat model, các hạn chế được ghi và review | Chủ repo tag/release hoặc yêu cầu sửa |

Chỉ chuyển tác vụ phụ thuộc khi owner đã review/merge mốc liên quan. Có thể tiếp tục soạn đặc tả,
đọc tài liệu hoặc sửa review trong thời gian chờ; không tự merge để giữ tiến độ.

BL-005 không giả lập balance/hold bằng 0 để cho đóng account. BL-006 phải khóa account khi provision Ledger,
đặt marker liên kết cùng transaction và nối closure guard với balance/pending work trước cash-in.
BL-011 khi thêm holds phải mở rộng guard và test hold-vs-close. Chi tiết tại
[đặc tả account](../api/account-lifecycle.md); đây là điều chỉnh ranh giới dependency, không thay thứ tự core.

## Phân công

| Việc | AI thực hiện | Owner thực hiện |
| --- | --- | --- |
| Bootstrap Spring Boot, Wrapper, Docker/CI | Tạo file, chạy công cụ sẵn có, báo kết quả thật | Review và hỗ trợ quyền/công cụ khi môi trường chặn |
| BRD/SRS/FSD theo chức năng, ADR/ERD | Soạn rules, state/API/data mapping, phản biện và cải thiện | Chốt scope, policy và acceptance criteria |
| Java, SQL migration, API docs | Viết/sửa code và Swagger trong cùng tác vụ | Review logic, readability, ranh giới module |
| Unit/integration/concurrency tests | Viết test có ý nghĩa, chạy và điều tra lỗi | Đánh giá coverage, tái lập/demo những case cần xác nhận |
| Git branch/commit/push/PR | Thực hiện trên feature branch trong phạm vi đã giao | Nhận xét từng commit, approve và quyết định merge |
| Review feedback | Đọc, giải thích, sửa bằng commit tiếp theo | Xác nhận thay đổi đáp ứng nhận xét |
| Secrets/tài khoản/cloud | Soạn cấu hình không chứa secrets, kiểm tra trên môi trường được cấp | Đăng nhập, cấp/thu hồi quyền, giữ secrets, quyết định chi phí |
| Nghiệp vụ thực/tuân thủ/production | Ghi giả định và giới hạn, hỗ trợ tài liệu | Mời domain/security expert, quyết định phạm vi triển khai thực |

AI chạy được Java/Maven/Docker trong workspace khi công cụ và quyền cho phép; không mặc định giao
mọi thao tác môi trường lại cho owner. Khi không làm được phải ghi lệnh, lỗi và bước owner cần làm,
phân biệt code chưa đúng với môi trường chưa sẵn sàng. Không yêu cầu owner tự tạo từng class Java.

## Nợ và hạng mục nâng cao

- OIDC từ S02 chưa tương đương security hoàn chỉnh: step-up/MFA, brute-force defense và policy vận hành
  cần threat model và tác vụ riêng khi mở rộng phạm vi.
- gRPC server streaming cho tỷ giá là extension sau core; không gắn vào transfer chỉ để có streaming.
- Lending, deposit interest, FX trading, card/PIN/ISO8583, kết nối mạng thanh toán thật và AML production
  nằm ngoài v1.0. Nếu bổ sung, phải lập use cases và roadmap mới.
- Nhánh chính, CI required check và quyền merge cần owner cấu hình theo khả năng gói GitHub.
