__kernel void gather_bytes( __global const uchar* src, __global const int* map, __global uchar* dst, const int mapAt,
		const int width ){
	int gid = get_global_id( 0 );
	int row = gid / width;
	int j = gid - row * width;
	int at = map[ mapAt + row ];
	dst[ gid ] = at >= 0 ? src[ (long) at * width + j ] : 0;
}

__kernel void gather_floats( __global const float* src, __global const int* map, __global float* dst, const int mapAt,
		const int width ){
	int gid = get_global_id( 0 );
	int row = gid / width;
	int j = gid - row * width;
	int at = map[ mapAt + row ];
	dst[ gid ] = at >= 0 ? src[ (long) at * width + j ] : 0.0f;
}

__kernel void gather_state( __global const float* states, __global const int* map, __global float* h0, __global float* c0,
		const int mapAt, const int s ){
	int gid = get_global_id( 0 );
	int chunk = gid / s;
	int j = gid - chunk * s;
	__global const float* state = states + (long) map[ mapAt + chunk ] * 2 * s;
	h0[ gid ] = state[ j ];
	c0[ gid ] = state[ s + j ];
}

float group_max( __local float* red, float v ){
	int lid = get_local_id( 0 );
	red[ lid ] = v;
	barrier( CLK_LOCAL_MEM_FENCE );
	for (int k = 32; k > 0; k >>= 1){
		if (lid < k) red[ lid ] = fmax( red[ lid ], red[ lid + k ] );
		barrier( CLK_LOCAL_MEM_FENCE );
	}
	float out = red[ 0 ];
	barrier( CLK_LOCAL_MEM_FENCE );
	return out;
}

float group_sum( __local float* red, float v ){
	int lid = get_local_id( 0 );
	red[ lid ] = v;
	barrier( CLK_LOCAL_MEM_FENCE );
	for (int k = 32; k > 0; k >>= 1){
		if (lid < k) red[ lid ] += red[ lid + k ];
		barrier( CLK_LOCAL_MEM_FENCE );
	}
	float out = red[ 0 ];
	barrier( CLK_LOCAL_MEM_FENCE );
	return out;
}

__kernel __attribute__(( reqd_work_group_size( 64, 1, 1 ) ))
void ppo_loss( __global const float* logits, __global const int* map, __global const int4* ints,
		__global const float4* floats, __global const uchar* masks, __global float* dLogits, __global float* rowStats,
		const int mapAt, const int K, const int A, const int S, const int T,
		const float epsilon, const float entropyCoeff, const float demoWeight, const float teacherWeight,
		const float valueCoeff, const float valueClip, const int huber ){
	__local float red[ 64 ];
	int row = get_group_id( 0 );
	int lid = get_local_id( 0 );
	__global const float* l = logits + (long) row * K;
	__global float* g = dLogits + (long) row * K;
	__global float* st = rowStats + row * 5;
	int at = map[ mapAt + row ];
	if (at < 0){
		for (int i = lid; i < K; i += 64) g[ i ] = 0.0f;
		if (lid < 5) st[ lid ] = 0.0f;
		return;
	}
	int4 meta = ints[ at ];
	float4 f = floats[ at ];
	int head = meta.x;
	int off = head == 1 ? A : head == 2 ? A + S : 0;
	int n = head == 1 ? S : head == 2 ? T : A;
	__global const uchar* m = masks + (long) at * ( K - 1 ) + off;
	__global const float* hl = l + off;

	float mx = -INFINITY;
	for (int i = lid; i < n; i += 64) if (m[ i ] && hl[ i ] > mx) mx = hl[ i ];
	mx = group_max( red, mx );
	float tot = 0.0f;
	for (int i = lid; i < n; i += 64) if (m[ i ]) tot += exp( hl[ i ] - mx );
	tot = group_sum( red, tot );
#define P( i ) ( m[ i ] && tot > 0.0f ? exp( hl[ i ] - mx ) / tot : 0.0f )
	float hh = 0.0f;
	for (int i = lid; i < n; i += 64){
		float p = P( i );
		if (p > 1e-12f) hh -= p * log( p );
	}
	float h = group_sum( red, hh );

	int teacher = meta.z;
	bool taught = teacher >= 0 && teacher < n && m[ teacher ];
	bool demo = meta.w != 0;
	int chosen = meta.y;
	float oldLog = f.x, advantage = f.y, target = f.z;
	float ratio = exp( log( fmax( P( chosen ), 1e-8f ) ) - oldLog );
	float low = 1.0f - epsilon, high = 1.0f + epsilon;
	bool stopped = ( advantage > 0.0f && ratio > high ) || ( advantage < 0.0f && ratio < low );
	float dlp = stopped ? 0.0f : advantage * ratio;
	float imitation = demo ? demoWeight : teacherWeight;

	for (int i = lid; i < K - 1; i += 64){
		float gi = 0.0f;
		int k = i - off;
		if (k >= 0 && k < n && m[ k ]){
			float p = P( k );
			if (!demo){
				gi -= dlp * ( ( k == chosen ? 1.0f : 0.0f ) - p );
				if (entropyCoeff != 0.0f && p > 1e-8f) gi -= entropyCoeff * ( -p * ( log( p ) + h ) );
			}
			if (imitation > 0.0f && taught) gi += imitation * ( p - ( k == teacher ? 1.0f : 0.0f ) );
		}
		g[ i ] = gi;
	}
	if (lid == 0){
		if (demo){
			for (int q = 0; q < 5; q++) st[ q ] = 0.0f;
			g[ K - 1 ] = 0.0f;
		} else {
			float value = l[ K - 1 ];
			float delta = value - target;
			float clipped = clamp( ratio, low, high );
			float magnitude = fabs( delta );
			st[ 0 ] = -fmin( ratio * advantage, clipped * advantage );
			st[ 1 ] = !huber || magnitude <= 1.0f ? 0.5f * delta * delta : magnitude - 0.5f;
			st[ 2 ] = h;
			st[ 3 ] = ( ratio - 1.0f ) - log( fmax( ratio, 1e-8f ) );
			st[ 4 ] = ratio < low || ratio > high ? 1.0f : 0.0f;
			g[ K - 1 ] = ( valueClip > 0.0f ? clamp( delta, -valueClip, valueClip ) : delta ) * valueCoeff;
		}
	}
#undef P
}

__kernel void loss_stats( __global const float* rowStats, __global float* stats, const int rows, const int slot ){
	int j = get_global_id( 0 );
	float sum = 0.0f;
	for (int r = 0; r < rows; r++) sum += rowStats[ r * 5 + j ];
	stats[ slot * 8 + j ] = sum;
}
