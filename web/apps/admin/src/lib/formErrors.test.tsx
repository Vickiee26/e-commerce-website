import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@shopflow/api-client'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { describe, expect, it } from 'vitest'
import { applyApiErrorToForm } from './formErrors'

type Values = { name: string; code: string }
const KNOWN = ['name', 'code'] as const

function Harness({ error }: { error: unknown }) {
  const { setError, formState } = useForm<Values>({ defaultValues: { name: '', code: '' } })
  const [banner, setBanner] = useState<string | null>('untouched')

  return (
    <div>
      <button type="button" onClick={() => setBanner(applyApiErrorToForm<Values>(error, setError, KNOWN))}>
        apply
      </button>
      <p data-testid="banner">{banner ?? 'no-banner'}</p>
      <p data-testid="name-error">{formState.errors.name?.message ?? ''}</p>
      <p data-testid="code-error">{formState.errors.code?.message ?? ''}</p>
    </div>
  )
}

describe('applyApiErrorToForm', () => {
  it('places each server field message on its own input and shows no banner', async () => {
    render(
      <Harness
        error={
          new ApiError(400, {
            detail: 'Validation failed',
            errors: [
              { field: 'code', message: 'must be lower-case letters, digits and single hyphens' },
              { field: 'name', message: 'must not be blank' },
            ],
          })
        }
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'apply' }))

    expect(screen.getByTestId('code-error')).toHaveTextContent(
      'must be lower-case letters, digits and single hyphens',
    )
    expect(screen.getByTestId('name-error')).toHaveTextContent('must not be blank')
    expect(screen.getByTestId('banner')).toHaveTextContent('no-banner')
  })

  it('keeps a message the form has no input for, rather than dropping it', async () => {
    render(
      <Harness
        error={new ApiError(400, { detail: 'Validation failed', errors: [{ field: 'sku', message: 'is unknown' }] })}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'apply' }))

    expect(screen.getByTestId('banner')).toHaveTextContent('Validation failed')
    expect(screen.getByTestId('banner')).toHaveTextContent('sku: is unknown')
  })

  it('falls back to detail for an error with no field messages', async () => {
    render(<Harness error={new ApiError(409, { title: 'Conflict', detail: 'Category code already exists' })} />)

    await userEvent.click(screen.getByRole('button', { name: 'apply' }))

    expect(screen.getByTestId('banner')).toHaveTextContent('Category code already exists')
  })

  it('reports something generic for a non-ApiError', async () => {
    render(<Harness error={new Error('boom')} />)

    await userEvent.click(screen.getByRole('button', { name: 'apply' }))

    expect(screen.getByTestId('banner')).toHaveTextContent('An unexpected error occurred')
  })
})
