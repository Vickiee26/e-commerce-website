import { zodResolver } from '@hookform/resolvers/zod'
import { useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { Button } from '../../components/Button'
import { ErrorPanel, Skeleton } from '../../components/QueryStates'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { useCategories } from '../categories/queries'
import { ProductFields } from './ProductFields'
import { PRODUCT_FIELDS, priceToNumber, productSchema, type ProductFormValues } from './productForm'
import { useCreateProduct } from './queries'

export function NewProductPage(): ReactElement {
  const categories = useCategories()
  const create = useCreateProduct()
  const navigate = useNavigate()
  const showToast = useToast()
  const [formMessage, setFormMessage] = useState<string | null>(null)

  const form = useForm<ProductFormValues>({
    resolver: zodResolver(productSchema),
    defaultValues: { name: '', description: '', price: '', categoryId: '', categoryTypeId: '' },
  })

  const onSubmit = form.handleSubmit((values) => {
    setFormMessage(null)
    create.mutate(
      {
        name: values.name,
        description: values.description === '' ? undefined : values.description,
        price: priceToNumber(values.price),
        categoryId: values.categoryId,
        categoryTypeId: values.categoryTypeId,
      },
      {
        onSuccess: (product) => {
          showToast(`Product "${product.name}" created`)
          // Straight to the detail screen: variants and images are added there, and a product
          // with no variants cannot be sold.
          void navigate(`/products/${product.id}`, { replace: true })
        },
        onError: (error) =>
          setFormMessage(applyApiErrorToForm<ProductFormValues>(error, form.setError, PRODUCT_FIELDS)),
      },
    )
  })

  return (
    <section className="flex max-w-2xl flex-col gap-4">
      <header className="flex flex-col gap-1">
        <Link to="/products" className="text-sm text-slate-600 underline-offset-2 hover:underline">
          ← Back to products
        </Link>
        <h1 className="text-xl font-semibold text-slate-900">New product</h1>
        <p className="text-sm text-slate-600">
          Variants and images come next, on the product’s own page.
        </p>
      </header>

      {categories.isPending ? <Skeleton rows={4} label="Loading categories" /> : null}

      {categories.isError ? (
        <ErrorPanel error={categories.error} onRetry={() => void categories.refetch()} />
      ) : null}

      {categories.isSuccess ? (
        <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
          <ProductFields form={form} categories={categories.data} />

          {formMessage !== null ? (
            <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
              {formMessage}
            </p>
          ) : null}

          <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
            <Button variant="secondary" onClick={() => void navigate('/products')}>
              Cancel
            </Button>
            <Button type="submit" loading={create.isPending}>
              Create product
            </Button>
          </div>
        </form>
      ) : null}
    </section>
  )
}
