import type { AdminVariant } from '@shopflow/api-client'
import { useEffect, useState, type FormEvent, type ReactElement } from 'react'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput, inputClass } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { describeError } from '../../lib/errors'
import { useAdjustStock } from './queries'

const QUANTITY_PATTERN = /^\d{1,9}$/

/**
 * The operator counts stock; the API takes movements. This dialog is the translation layer: it asks
 * for the quantity on the shelf and posts `target - current`, so nobody has to do signed mental
 * arithmetic at a stock take.
 */
export function StockDialog({
  open,
  onClose,
  productId,
  variant,
}: {
  open: boolean
  onClose: () => void
  productId: string
  variant: AdminVariant
}): ReactElement | null {
  const [target, setTarget] = useState(String(variant.stockQuantity))
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const adjust = useAdjustStock(productId)
  const showToast = useToast()

  useEffect(() => {
    if (!open) return
    setTarget(String(variant.stockQuantity))
    setReason('')
    setError(null)
  }, [open, variant.stockQuantity])

  const valid = QUANTITY_PATTERN.test(target)
  const delta = valid ? Number(target) - variant.stockQuantity : null

  const onSubmit = (event: FormEvent): void => {
    event.preventDefault()
    setError(null)

    if (delta === null) {
      setError('Enter a whole number of items')
      return
    }
    if (reason.trim() === '') {
      setError('A reason is required')
      return
    }
    // The backend's AssertTrue would answer 400; saying it here is faster and clearer.
    if (delta === 0) {
      setError('That is the current quantity — nothing to adjust')
      return
    }

    adjust.mutate(
      { variantId: variant.id, body: { delta, reason: reason.trim() } },
      {
        onSuccess: (adjustment) => {
          // The server's own before/after, not the numbers this dialog assumed.
          showToast(
            `${variant.color} / ${variant.size} stock ${adjustment.previousQuantity} → ${adjustment.newQuantity}`,
          )
          onClose()
        },
        // A 409 Insufficient stock carries the arithmetic in `detail`; show it verbatim.
        onError: (cause) => setError(describeError(cause).body),
      },
    )
  }

  return (
    <Dialog
      open={open}
      title={`Adjust stock — ${variant.color} / ${variant.size}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={adjust.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="stock-form" loading={adjust.isPending}>
            Adjust stock
          </Button>
        </>
      }
    >
      <form id="stock-form" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <p className="text-sm text-slate-600">{`Currently ${variant.stockQuantity} in stock.`}</p>

        <Field label="New quantity" htmlFor="stock-target" required>
          <TextInput
            id="stock-target"
            inputMode="numeric"
            autoComplete="off"
            value={target}
            onChange={(event) => setTarget(event.target.value)}
          />
        </Field>

        <p className="text-sm font-medium text-slate-700">
          {delta === null
            ? 'Enter a whole number of items'
            : delta === 0
              ? 'No change'
              : delta > 0
                ? `Adds ${delta} (${variant.stockQuantity} → ${variant.stockQuantity + delta})`
                : `Removes ${Math.abs(delta)} (${variant.stockQuantity} → ${variant.stockQuantity + delta})`}
        </p>

        <Field
          label="Reason"
          htmlFor="stock-reason"
          required
          hint="Recorded in the audit trail — it is the only record of why stock moved."
        >
          <textarea
            id="stock-reason"
            rows={2}
            maxLength={500}
            className={inputClass}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </Field>

        {error !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {error}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}
