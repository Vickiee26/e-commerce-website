import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'
import { Dialog } from './Dialog'

/**
 * The backdrop is whatever the panel sits in — expressed as a relationship rather than as
 * `.fixed.inset-0`, so restyling the overlay does not break a test about dismissal.
 */
function backdropOf(): HTMLElement {
  const backdrop = screen.getByRole('dialog').parentElement
  if (backdrop === null) throw new Error('the dialog panel has no backdrop to click')
  return backdrop
}

describe('Dialog', () => {
  it('renders nothing while closed', () => {
    render(
      <Dialog open={false} title="Edit category" onClose={vi.fn()}>
        body
      </Dialog>,
    )

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('exposes a labelled modal and closes on Escape and on the close button', async () => {
    const onClose = vi.fn()
    render(
      <Dialog open title="Edit category" onClose={onClose}>
        body
      </Dialog>,
    )

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName('Edit category')

    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })
})

describe('ConfirmDialog', () => {
  it('runs the confirm action and surfaces a failure without closing', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmDialog
        open
        title="Delete Abaya?"
        confirmLabel="Delete category"
        destructive
        error="Category is in use"
        onConfirm={onConfirm}
        onCancel={onCancel}
      >
        This deletes 1 type with it. This cannot be undone.
      </ConfirmDialog>,
    )

    expect(screen.getByText('This deletes 1 type with it. This cannot be undone.')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Category is in use')

    await userEvent.click(screen.getByRole('button', { name: 'Delete category' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('blocks the confirm button while busy', () => {
    render(
      <ConfirmDialog open busy title="Delete Abaya?" confirmLabel="Delete category" onConfirm={vi.fn()} onCancel={vi.fn()}>
        body
      </ConfirmDialog>,
    )

    expect(screen.getByRole('button', { name: 'Delete category' })).toBeDisabled()
  })

  it('blocks every route out (Escape, backdrop, Cancel, close) while busy', async () => {
    const onCancel = vi.fn()
    render(
      <ConfirmDialog open busy title="Delete Abaya?" confirmLabel="Delete category" onConfirm={vi.fn()} onCancel={onCancel}>
        body
      </ConfirmDialog>,
    )

    await userEvent.keyboard('{Escape}')
    expect(onCancel).not.toHaveBeenCalled()

    await userEvent.click(backdropOf())
    expect(onCancel).not.toHaveBeenCalled()

    // Both of these are visible while the request is in flight, so both have to be inert.
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('allows dismissal via Escape and backdrop when not busy', async () => {
    const onCancel = vi.fn()
    render(
      <ConfirmDialog open title="Delete Abaya?" confirmLabel="Delete category" onConfirm={vi.fn()} onCancel={onCancel}>
        body
      </ConfirmDialog>,
    )

    await userEvent.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalledTimes(1)

    await userEvent.click(backdropOf())
    expect(onCancel).toHaveBeenCalledTimes(2)
  })
})
