/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

#include <executorch/kernels/portable/cpu/util/padding_util.h>
#include <executorch/runtime/kernel/kernel_includes.h>

namespace torch {
namespace executor {
namespace native {

using Tensor = executorch::aten::Tensor;

Tensor& replication_pad1d_out(
    KernelRuntimeContext& ctx,
    const Tensor& in,
    executorch::aten::ArrayRef<int64_t> padding,
    Tensor& out) {
  (void)ctx;

  ET_KERNEL_CHECK(
      ctx, check_padding_args(1, in, padding, out), InvalidArgument, out);

  Tensor::SizesType target_sizes[kTensorDimensionLimit];
  size_t target_ndim = 0;
  get_padding_out_target_size(1, in, padding, target_sizes, &target_ndim);

  ET_KERNEL_CHECK(
      ctx,
      resize_tensor(out, {target_sizes, target_ndim}) == Error::Ok,
      InvalidArgument,
      out);

  ScalarType in_type = in.scalar_type();
  constexpr auto name = "replication_pad1d.out";

  ET_SWITCH_ALL_TYPES(in_type, ctx, name, CTYPE, [&] {
    pad1d<CTYPE>(replication_ix, in, out, padding);
  });

  return out;
}

} // namespace native
} // namespace executor
} // namespace torch
