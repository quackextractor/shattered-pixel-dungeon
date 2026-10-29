__kernel void sum_squares( __global const float* g, __global float* partial, const int total, const int groups ){
	int gid = get_global_id( 0 );
	float s = 0.0f;
	for (int i = gid; i < total; i += groups){
		float v = g[ i ];
		s += v * v;
	}
	partial[ gid ] = s;
}

__kernel void adam( __global float* p, __global const float* g, __global float* m, __global float* v,
		const float lr, const float beta1, const float beta2, const float eps, const float scale,
		const float bias1, const float bias2 ){
	int i = get_global_id( 0 );
	float gi = g[ i ] * scale;
	float mm = beta1 * m[ i ] + ( 1.0f - beta1 ) * gi;
	float vv = beta2 * v[ i ] + ( 1.0f - beta2 ) * gi * gi;
	m[ i ] = mm;
	v[ i ] = vv;
	p[ i ] -= lr * ( mm / bias1 ) / ( sqrt( vv / bias2 ) + eps );
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
