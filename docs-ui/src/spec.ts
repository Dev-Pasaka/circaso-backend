/** The part of OpenAPI 3.1 the reference reads. Everything the page shows comes from a document of this shape. */

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

export interface Schema {
  $ref?: string;
  type?: string | string[];
  title?: string;
  description?: string;
  format?: string;
  properties?: Record<string, Schema>;
  required?: string[];
  items?: Schema;
  enum?: Json[];
  additionalProperties?: boolean | Schema;
  oneOf?: Schema[];
  anyOf?: Schema[];
  allOf?: Schema[];
  example?: Json;
  default?: Json;
}

export interface Parameter {
  name: string;
  in: 'query' | 'header' | 'path' | 'cookie';
  description?: string;
  required?: boolean;
  schema?: Schema;
}

export interface MediaType {
  schema?: Schema;
}

export interface Response {
  description?: string;
  content?: Record<string, MediaType>;
}

export interface Operation {
  tags?: string[];
  summary?: string;
  description?: string;
  operationId?: string;
  parameters?: Parameter[];
  requestBody?: { description?: string; required?: boolean; content?: Record<string, MediaType> };
  responses?: Record<string, Response>;
  security?: Record<string, string[]>[];
}

export interface TagInfo {
  name: string;
  description?: string;
}

export interface OpenApi {
  openapi: string;
  info: { title: string; version: string; description?: string; contact?: { name?: string; url?: string } };
  servers?: { url: string; description?: string }[];
  paths: Record<string, Record<string, Operation | unknown>>;
  components?: { schemas?: Record<string, Schema>; securitySchemes?: Record<string, unknown> };
  tags?: TagInfo[];
  'x-tagGroups'?: { name: string; tags: string[] }[];
}

export const METHODS = ['get', 'post', 'put', 'patch', 'delete', 'head', 'options'] as const;
export type Method = (typeof METHODS)[number];
