import { describe, it, expect } from 'vitest'
import {
  guessMapping,
  mappingError,
  progress,
  duration,
  number,
  dateTime,
} from './utils'
import type { FieldMapping, FieldName } from './types'

const validMapping: FieldMapping = {
  sku: 'SKU',
  name: 'Name',
  category: 'Category',
  price: 'Price',
  stock: 'Stock',
}
const headers = Object.values(validMapping)

describe('guessMapping', () => {
  const aliases: Record<FieldName, string[]> = {
    sku: ['sku', 'productcode', 'productid', '商品编码', '商品编号', '编码', '货号'],
    name: ['name', 'productname', '商品名称', '名称'],
    category: ['category', 'type', '商品分类', '分类', '类别'],
    price: ['price', 'unitprice', '商品价格', '价格', '单价'],
    stock: ['stock', 'inventory', 'quantity', '商品库存', '库存', '库存数量'],
  }

  for (const field of Object.keys(aliases) as FieldName[]) {
    it.each(aliases[field])(`maps alias "%s" to ${field}`, alias => {
      expect(guessMapping([alias])[field]).toBe(alias)
    })
  }

  it('ignores case, spaces, underscores, and hyphens while preserving headers', () => {
    expect(
      guessMapping([
        ' ProDuct_Code ',
        'PRODUCT-NAME',
        ' Ca te_go-ry ',
        'UNIT_PRICE',
        ' InVen-Tory ',
      ]),
    ).toEqual({
      sku: ' ProDuct_Code ',
      name: 'PRODUCT-NAME',
      category: ' Ca te_go-ry ',
      price: 'UNIT_PRICE',
      stock: ' InVen-Tory ',
    })
  })

  it('ignores separators inside Chinese aliases', () => {
    expect(
      guessMapping([
        ' 商品_编码 ',
        '商品-名称',
        '商品 分类',
        '商品_价格',
        '库 存-数_量',
      ]),
    ).toEqual({
      sku: ' 商品_编码 ',
      name: '商品-名称',
      category: '商品 分类',
      price: '商品_价格',
      stock: '库 存-数_量',
    })
  })

  it('leaves unmatched fields empty', () => {
    expect(guessMapping(['Unknown'])).toEqual({
      sku: '',
      name: '',
      category: '',
      price: '',
      stock: '',
    })
  })

  it('uses the first matching header', () => {
    expect(guessMapping(['商品编码', 'SKU']).sku).toBe('商品编码')
  })
})

describe('mappingError', () => {
  it.each([
    ['sku', '商品编码'],
    ['name', '商品名称'],
    ['price', '商品价格'],
    ['stock', '商品库存'],
  ] as const)('rejects an empty required field: %s', (field, label) => {
    expect(mappingError({ ...validMapping, [field]: '' }, headers)).toBe(
      `请选择“${label}”对应的文件列。`,
    )
  })

  it('allows an empty optional category', () => {
    expect(
      mappingError(
        { ...validMapping, category: '' },
        headers.filter(header => header !== validMapping.category),
      ),
    ).toBeNull()
  })

  it.each([
    ['sku', '商品编码'],
    ['name', '商品名称'],
    ['category', '商品分类'],
    ['price', '商品价格'],
    ['stock', '商品库存'],
  ] as const)('rejects a missing header for %s', (field, label) => {
    expect(
      mappingError(
        validMapping,
        headers.filter(header => header !== validMapping[field]),
      ),
    ).toBe(`“${label}”对应的文件列已失效，请重新选择。`)
  })

  it('returns null for a valid mapping', () => {
    expect(mappingError(validMapping, headers)).toBeNull()
  })
})

describe('progress', () => {
  it.each([
    [-10, 0],
    [0, 0],
    [42.5, 42.5],
    [100, 100],
    [150, 100],
    [NaN, 0],
    [Infinity, 0],
    [-Infinity, 0],
  ])('formats %s as %s', (value, expected) => {
    expect(progress(value)).toBe(expected)
  })
})

describe('duration', () => {
  const start = '2026-01-01T00:00:00.000Z'

  it('returns 尚未开始 when the start is null', () => {
    expect(duration(null, null)).toBe('尚未开始')
    expect(duration(null, start)).toBe('尚未开始')
  })

  it.each([
    ['2026-01-01T00:00:00.000Z', '0 秒'],
    ['2026-01-01T00:00:01.999Z', '1 秒'],
    ['2026-01-01T00:00:59.000Z', '59 秒'],
    ['2026-01-01T00:01:00.000Z', '1 分 0 秒'],
    ['2026-01-01T00:02:05.000Z', '2 分 5 秒'],
    ['2026-01-01T00:59:59.000Z', '59 分 59 秒'],
    ['2026-01-01T01:00:00.000Z', '1 小时 0 分'],
    ['2026-01-01T02:03:59.000Z', '2 小时 3 分'],
  ])('formats elapsed time ending at %s', (end, expected) => {
    expect(duration(start, end)).toBe(expected)
  })

  it('clamps negative elapsed time to zero', () => {
    expect(duration(start, '2025-12-31T23:59:59.000Z')).toBe('0 秒')
  })
})

describe('number', () => {
  it.each([null, undefined])('returns — for %s', value => {
    expect(number(value)).toBe('—')
  })

  it.each([NaN, Infinity, -Infinity])('returns — for invalid number %s', value => {
    expect(number(value)).toBe('—')
  })

  it.each([
    [0, '0'],
    [1234567.89, '1,234,567.89'],
    [-1234.5, '-1,234.5'],
  ])('formats %s as %s', (value, expected) => {
    expect(number(value)).toBe(expected)
  })
})

describe('dateTime', () => {
  it.each([null, undefined, '', 'not-a-date', '2026-13-01T00:00:00Z'])(
    'returns — for invalid or missing input %s',
    value => {
      expect(dateTime(value)).toBe('—')
      expect(dateTime(value, true)).toBe('—')
    },
  )

  const iso = '2026-06-15T12:34:56.000Z'

  it('formats a valid ISO date with year and seconds', () => {
    const expected = new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(new Date(iso))

    expect(dateTime(iso)).toBe(expected)
    expect(dateTime(iso, false)).toBe(expected)
    expect(dateTime(iso)).not.toMatch(/Invalid Date|NaN|undefined/)
  })

  it('formats a valid ISO date compactly without year or seconds', () => {
    const expected = new Intl.DateTimeFormat('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(new Date(iso))

    expect(dateTime(iso, true)).toBe(expected)
    expect(dateTime(iso, true)).not.toMatch(/Invalid Date|NaN|undefined/)
  })

  it('formats equivalent ISO instants consistently', () => {
    const equivalent = '2026-06-15T20:34:56.000+08:00'

    expect(dateTime(equivalent)).toBe(dateTime(iso))
    expect(dateTime(equivalent, true)).toBe(dateTime(iso, true))
  })
})
