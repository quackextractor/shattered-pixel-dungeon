__kernel void sum_squares( __global const float* g, __global float* partial, const int total, const int groups ){
	int gid = get_global_id( 0 );
	float s = 0.0f;
	for (int i = gid; i < total; i += groups){
		float v = g[ i ];
		s += v * v;
	}
	partial[ gid ] = s;
}

__kernel void pack_columns( __global const float* src, __global float* dst, const int out, const int K, const int off ){
	int gid = get_global_id( 0 );
	int m = gid / out;
	int j = gid - m * out;
	dst[ m * K + off + j ] = src[ gid ];
}

__kernel void unpack_columns( __global const float* src, __global float* dst, const int out, const int K, const int off ){
	int gid = get_global_id( 0 );
	int m = gid / out;
	int j = gid - m * out;
	dst[ gid ] = src[ m * K + off + j ];
}

__kernel void grad_scale( __global const float* partial, __global float* scale, __global float* stats, const int groups,
		const float count, const float maxNorm, const int slot ){
	__local float part[ 256 ];
	int lid = get_local_id( 0 );
	float sum = 0.0f;
	for (int i = lid; i < groups; i += 256) sum += partial[ i ];
	part[ lid ] = sum;
	barrier( CLK_LOCAL_MEM_FENCE );
	for (int k = 128; k > 0; k >>= 1){
		if (lid < k) part[ lid ] += part[ lid + k ];
		barrier( CLK_LOCAL_MEM_FENCE );
	}
	if (lid == 0){
		float norm = sqrt( part[ 0 ] ) / count;
		float s = 1.0f / count;
		if (maxNorm > 0.0f && norm > maxNorm) s *= maxNorm / norm;
		scale[ 0 ] = s;
		stats[ slot * 8 + 5 ] = norm;
	}
}

__kernel void adam_scaled( __global float* p, __global const float* g, __global float* m, __global float* v,
		__global const float* scaleBuf, const float lr, const float beta1, const float beta2, const float eps,
		const float bias1, const float bias2 ){
	int i = get_global_id( 0 );
	float gi = g[ i ] * scaleBuf[ 0 ];
	float mm = beta1 * m[ i ] + ( 1.0f - beta1 ) * gi;
	float vv = beta2 * v[ i ] + ( 1.0f - beta2 ) * gi * gi;
	m[ i ] = mm;
	v[ i ] = vv;
	p[ i ] -= lr * ( mm / bias1 ) / ( sqrt( vv / bias2 ) + eps );
}
