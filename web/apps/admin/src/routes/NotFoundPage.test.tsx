import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/render'
import { NotFoundPage } from './NotFoundPage'

describe('NotFoundPage', () => {
  it('explains the miss and offers a way back to the catalogue', () => {
    renderWithProviders(<NotFoundPage />, { route: '/nope' })

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to products' })).toHaveAttribute('href', '/products')
  })
})
