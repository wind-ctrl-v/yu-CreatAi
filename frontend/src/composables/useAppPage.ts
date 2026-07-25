import { ref } from 'vue'
import type { AppQueryRequest, AppVO, BaseResponse, Page } from '../types'

type AppPageFetcher = (params: AppQueryRequest) => Promise<BaseResponse<Page<AppVO>>>

export function useAppPage(fetcher: AppPageFetcher, initialPageSize = 12) {
  const records = ref<AppVO[]>([])
  const loading = ref(false)
  const total = ref(0)
  const pageNum = ref(1)
  const pageSize = ref(initialPageSize)

  async function load(extra: Omit<AppQueryRequest, 'pageNum' | 'pageSize'> = {}) {
    loading.value = true
    try {
      const response = await fetcher({
        ...extra,
        pageNum: pageNum.value,
        pageSize: pageSize.value,
      }).catch(() => null)
      if (response?.code === 0) {
        records.value = response.data.records || []
        total.value = response.data.totalRow || 0
      }
    } finally {
      loading.value = false
    }
  }

  async function changePage(page: number, extra: Omit<AppQueryRequest, 'pageNum' | 'pageSize'> = {}) {
    pageNum.value = page
    await load(extra)
  }

  async function resetAndLoad(extra: Omit<AppQueryRequest, 'pageNum' | 'pageSize'> = {}) {
    pageNum.value = 1
    await load(extra)
  }

  return { records, loading, total, pageNum, pageSize, load, changePage, resetAndLoad }
}
