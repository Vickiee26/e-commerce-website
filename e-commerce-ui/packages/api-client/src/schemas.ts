import type { components } from './generated'

type Schemas = components['schemas']

/** Makes the listed keys required and non-nullable, leaving the rest as generated. */
type RequireKeys<T, K extends keyof T> = Omit<T, K> & { [P in K]-?: NonNullable<T[P]> }

// Requests are used verbatim: their `required` arrays are already correct.
export type LoginRequest = Schemas['LoginRequest']
export type RefreshRequest = Schemas['RefreshRequest']
export type LogoutRequest = Schemas['LogoutRequest']
export type CreateCategoryRequest = Schemas['CreateCategoryRequest']
export type UpdateCategoryRequest = Schemas['UpdateCategoryRequest']
export type CreateCategoryTypeRequest = Schemas['CreateCategoryTypeRequest']
export type UpdateCategoryTypeRequest = Schemas['UpdateCategoryTypeRequest']
export type CreateProductRequest = Schemas['CreateProductRequest']
export type UpdateProductRequest = Schemas['UpdateProductRequest']
export type CreateVariantRequest = Schemas['CreateVariantRequest']
export type UpdateVariantRequest = Schemas['UpdateVariantRequest']
export type CreateResourceRequest = Schemas['CreateResourceRequest']
export type UpdateResourceRequest = Schemas['UpdateResourceRequest']

/**
 * `deltaNonZero` is an @AssertTrue validator leaking into the generated document. Omitting
 * it here makes sending it a type error rather than a puzzling 400.
 */
export type AdjustStockRequest = Omit<Schemas['AdjustStockRequest'], 'deltaNonZero'>

export type TokenPair = RequireKeys<
  Schemas['TokenPairResponse'],
  'accessToken' | 'refreshToken' | 'tokenType' | 'expiresIn'
>

export type UserProfile = RequireKeys<
  Schemas['UserProfileResponse'],
  'id' | 'email' | 'fullName' | 'emailVerified' | 'roles' | 'createdAt'
>

export type CategoryType = RequireKeys<Schemas['CategoryTypeResponse'], 'id' | 'code' | 'name'>

export type Category = RequireKeys<
  Omit<Schemas['CategoryResponse'], 'types'>,
  'id' | 'code' | 'name'
> & { types: CategoryType[] }

/**
 * The generated document says `archivedAt?: string`, but the backend serialises nulls, so a LIVE
 * row arrives carrying `archivedAt: null` rather than omitting the field. Admitting that null is
 * what forces every call site to decide about both spellings of "not archived" — comparing against
 * `undefined` alone made live rows look archived, which no fixture-based test could catch.
 */
type Archivable = { archivedAt?: string | null }

export type AdminVariant = RequireKeys<
  Omit<Schemas['AdminVariantResponse'], 'archivedAt'>,
  'id' | 'color' | 'size' | 'stockQuantity'
> &
  Archivable

export type ProductResource = RequireKeys<Schemas['ProductResourceDto'], 'id' | 'url' | 'isPrimary'>

/** `variantCount` and `totalStock` cover unarchived variants only. */
export type AdminProductSummary = RequireKeys<
  Omit<Schemas['AdminProductSummaryResponse'], 'archivedAt'>,
  | 'id'
  | 'name'
  | 'price'
  | 'categoryId'
  | 'categoryName'
  | 'categoryTypeId'
  | 'categoryTypeName'
  | 'variantCount'
  | 'totalStock'
> &
  Archivable

export type AdminProduct = RequireKeys<
  Omit<Schemas['AdminProductResponse'], 'variants' | 'resources' | 'archivedAt'>,
  'id' | 'name' | 'price' | 'categoryId' | 'categoryName' | 'categoryTypeId' | 'categoryTypeName'
> &
  Archivable & { variants: AdminVariant[]; resources: ProductResource[] }

export type StockAdjustment = RequireKeys<
  Schemas['StockAdjustmentResponse'],
  'variantId' | 'previousQuantity' | 'newQuantity' | 'delta' | 'reason'
>

export type AdminProductPage = RequireKeys<
  Omit<Schemas['PageResponseAdminProductSummaryResponse'], 'content'>,
  'page' | 'size' | 'totalElements' | 'totalPages' | 'first' | 'last'
> & { content: AdminProductSummary[] }
