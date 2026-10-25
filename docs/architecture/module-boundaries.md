# BL-002 — Quy tắc module trong core

Root `vn.banklab.core`. Một module nghiệp vụ là package cấp một, hiện có `system`; Customer, Account,
Ledger và Transfer sẽ được thêm qua tác vụ tương ứng. `shared` là hạ tầng dùng chung, `config` là composition root.
Không tạo hàng loạt class/package rỗng chỉ để thể hiện kiến trúc mục tiêu.

```text
vn.banklab.core
  CoreBankingApplication
  config                    # Ghép cấu hình toàn ứng dụng
  shared
    application             # Business failure contract thuần Java
    web                     # Correlation, Problem Details, API error annotation
  system
    api                     # HTTP controller và DTO
  <business-module>          # Khi tác vụ nghiệp vụ được triển khai
    api                     # REST adapters
    application             # Use cases và contracts gọi từ module khác
    domain                  # Model/rules thuần Java
    infrastructure          # Persistence, messaging, adapters
```

## Quy tắc được kiểm tra bằng ArchUnit

1. Các package cấp một không tạo dependency cycle.
2. Module chỉ truy cập module khác qua package `application`; không gọi controller, repository hoặc entity
   nội bộ của module khác. Module có thể dùng `shared`. Composition root `config` có quyền ghép các thành phần.
3. `shared` không phụ thuộc module nghiệp vụ hoặc config; không chuyển logic riêng của Account/Ledger vào shared
   để lách ranh giới. Shared không sở hữu repository nghiệp vụ.
4. `domain` không phụ thuộc Spring, JPA, API/web/application/infrastructure. BL-002 chưa có domain classes;
   chỉ rule này cho phép tập rỗng, sẽ tự áp dụng khi domain được thêm.
5. REST controller nằm trong package `api` và khai báo `@ApiErrorResponses`.

Test chạy trên production bytecode, loại bỏ test fixtures. Rules áp dụng động cho module mới, không chỉ hardcode
system/account. ArchUnit kiểm tra dependency ở compile time; không chứng minh được transaction boundary,
reflection hoặc SQL động. Các phần đó vẫn cần tests nghiệp vụ/integration và review.

## Gọi giữa module và transaction

Gọi application contract bằng Java trong cùng deployable. Những contract này không phơi bày JPA entities
hoặc repository. Không gọi REST/gRPC giữa các module chỉ vì package khác nhau.
Khi có nhiều module, tránh hai application services gọi qua lại; xác định orchestration direction theo use case.
Account/Ledger/Transfer vẫn cùng DB consistency boundary theo ADR-001; kiểm tra kiến trúc không thay thế
atomic financial commit, lock order, idempotency hoặc reconciliation tests.

Nguồn công cụ: [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html).
