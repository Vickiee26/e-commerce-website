import { zodResolver } from '@hookform/resolvers/zod'
import type { AdminProduct, UpdateProductRequest } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { Button } from '../../components/Button'
import { ErrorPanel, Skeleton } from '../../components/QueryStates'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { useCategories } from '../categories/queries'
import { ProductFields } from './ProductFields'
import { PRODUCT_FIELDS, priceToNumber, productSchema, type ProductFormValues } from './productForm'
import { useUpdateProduct } from './queries'

/** The form holds price as a string, and this is the only place the number becomes one. */
function toFormValues(product: AdminProduct): ProductFormValues {
  return {
    name: product.name,
    description: product.description ?? '',
    price: product.price.toFixed(2),
    categoryId: product.categoryId,
    categoryTypeId: product.categoryTypeId,
  }
}

export function ProductDetailsCard({ product }: { product: AdminProduct }): ReactElement {
  const categories = useCategories()
  const update = useUpdateProduct(product.id)
  const showToast = useToast()
  const [formMessage, setFormMessage] = useState<string | null>(null)

  const form = useForm<ProductFormValues>({
    resolver: zodResolver(productSchema),
    defaultValues: toFormValues(product),
  })

  /*
   * Read during render, which is the subscription react-hook-form documents: `formState` is a
   * Proxy that only starts tracking what it sees read while rendering. Reading dirtyFields for
   * the first time inside onSubmit happens to work in 7.85, but it leans on an implementation
   * detail, and an empty dirtyFields would silently turn every save into "Nothing to save".
   */
  const { isDirty, dirtyFields } = form.formState

  // A refetch or a restore replaces the product; the form follows unless the operator is mid-edit.
  useEffect(() => {
    if (!isDirty) form.reset(toFormValues(product))
  }, [product, form, isDirty])

  const onSubmit = form.handleSubmit((values) => {
    setFormMessage(null)
    const body: UpdateProductRequest = {}

    if (dirtyFields.name === true) body.name = values.name
    // '' is meaningful: the backend leaves a null field alone and applies an empty string, so this
    // is how a description is cleared.
    if (dirtyFields.description === true) body.description = values.description
    if (dirtyFields.price === true) body.price = priceToNumber(values.price)
    // The pair is validated together server-side; sending one alone would validate the new value
    // against the stored other one, which is exactly the 404 we designed out.
    if (dirtyFields.categoryId === true || dirtyFields.categoryTypeId === true) {
      body.categoryId = values.categoryId
      body.categoryTypeId = values.categoryTypeId
    }

    if (Object.keys(body).length === 0) {
      showToast('Nothing to save')
      return
    }

    update.mutate(body, {
      onSuccess: (updated) => {
        showToast('Product updated')
        form.reset(toFormValues(updated))
      },
      onError: (error) =>
        setFormMessage(applyApiErrorToForm<ProductFormValues>(error, form.setError, PRODUCT_FIELDS)),
    })
  })

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-lg font-semibold text-slate-900">Details</h2>

      {categories.isPending ? <Skeleton rows={4} label="Loading categories" /> : null}

      {categories.isError ? (
        <ErrorPanel error={categories.error} onRetry={() => void categories.refetch()} />
      ) : null}

      {categories.isSuccess ? (
        <form className="mt-4 flex flex-col gap-4" onSubmit={onSubmit} noValidate>
          <ProductFields form={form} categories={categories.data} />

          {formMessage !== null ? (
            <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
              {formMessage}
            </p>
          ) : null}

          <div className="flex justify-end">
            <Button type="submit" loading={update.isPending}>
              Save details
            </Button>
          </div>
        </form>
      ) : null}
    </section>
  )
}
