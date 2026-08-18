import { ApiError, NetworkError } from '@shopflow/api-client'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { EmptyState, ErrorPanel, Skeleton } from './QueryStates'

describe('Skeleton', () => {
  it('announces that something is loading', () => {
    render(<Skeleton rows={2} label="Loading products" />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading products')
  })
})

describe('ErrorPanel', () => {
  it('offers Retry for a server failure', async () => {
    const onRetry = vi.fn()
    render(<ErrorPanel error={new ApiError(500, { detail: 'Internal error' })} onRetry={onRetry} />)

    expect(screen.getByRole('heading', { name: 'The server failed' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('words a network failure differently from a server failure', () => {
    render(<ErrorPanel error={new NetworkError(new TypeError('Failed to fetch'))} />)

    expect(screen.getByRole('heading', { name: 'Cannot reach the server' })).toBeInTheDocument()
  })

  it('does not offer Retry for a 403, which would answer the same way', () => {
    render(<ErrorPanel error={new ApiError(403, { title: 'Forbidden' })} onRetry={vi.fn()} />)

    expect(screen.getByText('You do not have permission for this action.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })
})

describe('EmptyState', () => {
  it('shows a heading, an explanation and the primary action', () => {
    render(
      <EmptyState
        title="No categories yet"
        description="A product needs a category and a type before it can exist."
        action={<button type="button">Create your first category</button>}
      />,
    )

    expect(screen.getByRole('heading', { name: 'No categories yet' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create your first category' })).toBeInTheDocument()
  })
})
