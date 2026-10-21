import type { FlatResponseData } from '@sa/axios';
import { request } from '../request';

export function fetchGetOrgTagTree() {
  return request<Api.OrgTag.Item[]>({ url: '/admin/org-tags/tree' });
}

export async function fetchGetOrgTagList(
  params: Api.Common.CommonSearchParams = {}
): Promise<FlatResponseData<Api.OrgTag.List>> {
  const response = await request<Api.OrgTag.Item[] | Api.OrgTag.List>({ url: '/admin/org-tags/tree', params });
  if (response.error) return response as FlatResponseData<Api.OrgTag.List>;

  const payload = response.data;
  if (!Array.isArray(payload)) return response as FlatResponseData<Api.OrgTag.List>;

  const page = params.page && params.page > 0 ? params.page : 1;
  const size = params.size && params.size > 0 ? params.size : 10;
  const start = (page - 1) * size;
  const pageData = payload.slice(start, start + size);

  return {
    ...response,
    data: {
      data: pageData,
      content: pageData,
      number: page,
      size,
      totalElements: payload.length
    }
  };
}
