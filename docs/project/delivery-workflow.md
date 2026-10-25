# Git workflow và Definition of Done

## Trạng thái tác vụ

Proposed → Ready (owner chốt rules/AC) → In progress → In review → Merged/Done.
Nếu thiếu môi trường, ghi Blocked kèm lỗi cụ thể và tiếp tục các phần không bị chặn.
CI xanh không tự chuyển Done; owner giữ quyền approve và merge.

## Một vòng triển khai

1. Issue có use case, business rules, scope/exclusions, API/data thay đổi, acceptance cases,
   dependencies và câu hỏi quyết định. Dùng mã BL + số GitHub issue thực.
2. Tạo branch từ `main` mới nhất: `chore/BL-001-bootstrap`, `feat/BL-008-internal-transfer`,
   `fix/BL-009-duplicate-request`. Không đẩy code trực tiếp lên main.
3. Implement một phạm vi review được. Cập nhật migrations, tests và Swagger cùng hành vi.
4. Commit theo ý nghĩa: platform/domain → test/contract → docs/ops, thường 1–3 commit/PR.
   Không chia cứng theo loại file nếu làm commit mất khả năng build hoặc review nghiệp vụ.
5. Chạy checks; báo rõ các phần chưa chạy. Push branch, mở Draft PR, link issue và evidence.
6. Owner review từng commit, có thể comment vào diff dòng cụ thể; AI sửa bằng commit mới để giữ dấu vết.
7. Khi checks đạt và owner đồng ý phạm vi, owner quyết định approve/merge. Không force-push/rewrite
   các commit đã review khi chưa có thỏa thuận. Không tự merge hoặc bật auto-merge.
8. Sau merge, cập nhật local main bằng fast-forward và tạo branch cho tác vụ phụ thuộc tiếp theo.

GitHub Flow được chọn vì một dòng release; chưa cần nhánh develop tồn tại dài hạn.
Nếu owner muốn giữ từng commit trong lịch sử main, chọn merge commit/rebase theo policy của repo;
squash vẫn cho review từng commit trước merge nhưng gom lịch sử trên main.

## Conventional Commits

```text
chore(platform): bootstrap Spring Boot and PostgreSQL
test(platform): verify migrations and Swagger against PostgreSQL
docs(project): define delivery plan and review gates
feat(ledger): enforce balanced journal posting
fix(transfer): prevent concurrent overspending
```

Tên commit nói hành vi, không chỉ nói “update files”. PR title gồm mã BL.
Review code theo business invariant, không chỉ xem CI pass hay số lượng test.

## Nội dung tối thiểu của đặc tả mỗi chức năng

- Actor, quyền/ownership, preconditions, happy path, alternate/error paths.
- Rules có ID, state transitions và thời điểm bắt buộc kiểm tra trong transaction.
- API path/method, DTO, validation, HTTP/business errors, ví dụ, idempotency scope/replay/conflict.
- Entity/constraint/index, transaction boundary, locking order và recovery khi timeout/crash.
- Acceptance scenarios ánh xạ rule → code/API/database → test/evidence.
- NFR liên quan, audit/privacy, observability, migrations và giới hạn còn lại.

Không viết một đặc tả khổng lồ rồi xem mọi quyết định đã chốt. Phiên A cập nhật contract của tác vụ
và owner xác nhận các policy trước phần code phụ thuộc vào chúng.

## Definition of Done

- Scope và AC được owner chấp thuận; diff không kèm việc ngoài scope.
- Chạy unit tests và integration phù hợp; `clean verify` đạt ở CI. Các case tiền phải có atomicity,
  duplicate request, concurrency, timeout/retry và invariants tương ứng; không thay PostgreSQL bằng H2.
- Swagger phản ánh đúng request/response, mã lỗi, auth và idempotency. Có negative contract tests khi cần.
- Migration forward-only, không sửa migration đã áp dụng, mô tả cách recovery khi lỗi.
- Không có credentials/PII thực; log/audit/event payload chỉ mang dữ liệu cần thiết.
- PR nêu kết quả, lệnh kiểm chứng, rủi ro còn lại; comments đã xử lý hoặc owner đồng ý hoãn rõ ràng.
- Owner review từng commit và merge. Issue chỉ đóng khi điều kiện này đạt.

## Cấu hình owner trên GitHub

Đề xuất ruleset cho `main`: bắt buộc PR, required CI job `Maven verify (Java 21 + PostgreSQL 16)`,
resolve conversations và chặn force-push/deletion. Số approval và quyền bypass tùy số người/gói GitHub.
Với repo chỉ một account author/owner, GitHub không cho author approve PR của chính mình; owner vẫn
review diff và quyết định merge, hoặc dùng reviewer account khác khi cần enforced approval.
AI không tự thay đổi quyền repository hoặc tự gửi approval thay owner.
