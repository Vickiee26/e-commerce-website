import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'
import { Dialog } from './Dialog'

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

  it('blocks all dismissal (Escape, backdrop, Cancel) while busy', async () => {
    const onCancel = vi.fn()
    const { container } = render(
      <ConfirmDialog open busy title="Delete Abaya?" confirmLabel="Delete category" onConfirm={vi.fn()} onCancel={onCancel}>
        body
      </ConfirmDialog>,
    )

    await userEvent.keyboard('{Escape}')
    expect(onCancel).not.toHaveBeenCalled()

    const backdrop = container.querySelector('.fixed.inset-0')
    await userEvent.click(backdrop!)
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('allows dismissal via Escape and backdrop when not busy', async () => {
    const onCancel = vi.fn()
    const { container } = render(
      <ConfirmDialog open title="Delete Abaya?" confirmLabel="Delete category" onConfirm={vi.fn()} onCancel={onCancel}>
        body
      </ConfirmDialog>,
    )

    await userEvent.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalledTimes(1)

    const backdrop = container.querySelector('.fixed.inset-0')
    await userEvent.click(backdrop!)
    expect(onCancel).toHaveBeenCalledTimes(2)
  })
})
