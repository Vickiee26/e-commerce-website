import type { AdminProductSummary } from '@shopflow/api-client'
import type { ReactElement } from 'react'
import { Link } from 'react-router'
import { Badge } from '../../components/Badge'
import { formatUsd } from '../../lib/format'

/**
 * One data source, two layouts: a table from `md` up, cards below. Anything derived lives in
 * `StatusBadges` so the two can never disagree about what a row means.
 */
export function ProductList({ products }: { products: AdminProductSummary[] }): ReactElement {
  return (
    <>
      <table className="hidden w-full border-collapse text-sm md:table">
        <thead>
          <tr className="border-b border-slate-200 text-left text-slate-600">
            <th scope="col" className="py-2 pr-3 font-medium">
              Product
            </th>
            <th scope="col" className="py-2 pr-3 font-medium">
              Category
            </th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">
              Price
            </th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">
              Live variants
            </th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">
              Live stock
            </th>
            <th scope="col" className="py-2 font-medium">
              Status
            </th>
          </tr>
        </thead>
        <tbody>
          {products.map((product) => (
            <tr key={product.id} className="border-b border-slate-100">
              <td className="py-3 pr-3">
                <Link
                  to={`/products/${product.id}`}
                  className="font-medium text-slate-900 underline-offset-2 hover:underline"
                >
                  {product.name}
                </Link>
              </td>
              <td className="py-3 pr-3 text-slate-600">
                {`${product.categoryName} / ${product.categoryTypeName}`}
              </td>
              <td className="py-3 pr-3 text-right tabular-nums">{formatUsd(product.price)}</td>
              <td className="py-3 pr-3 text-right tabular-nums">{product.variantCount}</td>
              <td className="py-3 pr-3 text-right tabular-nums">{product.totalStock}</td>
              <td className="py-3">
                <div className="flex flex-wrap gap-1">
                  <StatusBadges product={product} />
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <ul className="flex flex-col gap-3 md:hidden">
        {products.map((product) => (
          <li key={product.id} className="rounded-lg border border-slate-200 bg-white p-4">
            <div className="flex items-start justify-between gap-3">
              <Link to={`/products/${product.id}`} className="font-medium text-slate-900">
                {product.name}
              </Link>
              <span className="shrink-0 tabular-nums">{formatUsd(product.price)}</span>
            </div>
            <p className="mt-1 text-xs text-slate-500">
              {`${product.categoryName} / ${product.categoryTypeName}`}
            </p>
            <p className="mt-2 text-xs text-slate-600">
              {`${product.variantCount} live variant${product.variantCount === 1 ? '' : 's'} · ${product.totalStock} in stock`}
            </p>
            <div className="mt-2 flex flex-wrap gap-1">
              <StatusBadges product={product} />
            </div>
          </li>
        ))}
      </ul>
    </>
  )
}

function StatusBadges({ product }: { product: AdminProductSummary }): ReactElement {
  return (
    <>
      {product.archivedAt !== undefined ? <Badge tone="neutral">Archived</Badge> : null}
      {product.variantCount === 0 ? <Badge tone="warning">No variants</Badge> : null}
      {product.variantCount > 0 && product.totalStock === 0 ? (
        <Badge tone="danger">Out of stock</Badge>
      ) : null}
    </>
  )
}
