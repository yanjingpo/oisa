/***********************************
 * 
 * Copy-paste from wikicoding
 * 
 **********************************/ 
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <float.h>
#include "../include/asm.h"
#include "../include/scan_oram/scan_oram.h"

static int seed = 0;
static int one = 1;
static float max_val = 100.0;

static int dim = 2;
static int num_clusters = 32;
static int num_points = 1024;

float distance(float* p1, float* p2, int dim){
    float dist = 0.0;
    for(int i = 0; i < dim; i++)
        dist += pow(p1[i] - p2[i], 2);
    return sqrt(dist);
}

// A simple k-means clustering routine
// Return True  if final sum of distances < error before reaches num_iters
//        False if final sum of distances > error when reaches num_iters
// Parameters:  float** data:      array of data pointes
//              int num_points:     number of data points
//              int dim:            number of dimensions
//              int num_clusters:   desired number of clusters
//              float error:       used as the stopping criterion
//              float** centroids: output address of the final centroids
//              int* labels:        cluster labels of all the data points
int __attribute__((noinline)) Kmeans(float** data, float error, float** centroids, int* labels, int num_iters){

    float prev_total_dist = 0;
    float curr_total_dist = FLT_MAX;

    int* cluster_sizes = (int*) calloc(num_clusters, sizeof(int));
    float** temp_centroids = (float**) malloc(sizeof(float*) * dim);
    for(int i = 0; i < dim; i++)
        temp_centroids[i] = (float*) malloc(sizeof(float) * num_clusters);

    /* Initialization */
    for(int i = 0; i < num_clusters; i++)
        for(int j = 0; j < dim; j++)
            centroids[i][j] = data[i][j];

    /* Main Loop */
    for(int h = 0; h < num_iters; h++){
        prev_total_dist = curr_total_dist;
        curr_total_dist = 0;

        // find the closest centroid to every point
        for(int i = 0; i < num_points; i++){
            float min_dist = FLT_MAX;
            for(int j = 0; j < num_clusters; j++){
                float dist = 0;
                for(int k = 0; k < dim; k++)
                   dist += pow(data[i][k] - centroids[j][k], 2);

                // if dist < min_dist, label[i] = j, min_dist = dist
                int cent_closer = (dist < min_dist);
                _cmov(cent_closer, j,    &labels[i]);
                _cmov(cent_closer, dist, &min_dist);
            }
            curr_total_dist += min_dist;
        }

        // clear out temp centroids
        for(int i = 0; i < dim; i++)
            for(int j = 0; j < num_clusters; j++)
                temp_centroids[i][j] = 0.0;

        // clear out current cluster sizes
        for(int i = 0; i < num_clusters; i++){
            cluster_sizes[i] = 0;
        }

        // update temp centroid sum of destination cluster
        for(int i = 0; i < dim; i++){
            for(int j = 0; j < num_points; j++){
                // temp_centroids[i][labels[j]] += data[j][i];
                float centroid_val = 0;
                ScanORAM_Read((int*)temp_centroids[i], num_clusters, 1, (int*)&centroid_val, labels[j]);
                centroid_val += data[j][i];
                ScanORAM_Write((int*)temp_centroids[i], num_clusters, 1, (int*)&centroid_val, labels[j]);
            }
        }
        // update current cluster sizes
        for(int i = 0; i < num_points; i++){
            // cluster_sizes[labels[i]]++;
            int cluster_size = 0;
            ScanORAM_Read(cluster_sizes, num_clusters, 1, &cluster_size, labels[i]);
            cluster_size++;
            ScanORAM_Write(cluster_sizes, num_clusters, 1, &cluster_size, labels[i]);
        }

        // calculate the new centroids
        for(int i = 0; i < num_clusters; i++){
            for(int j = 0; j < dim; j++){
                // if cluster_sizes[i] == 0, to avoid divide_by_zero, we force cluster_size[i] = 1
                int cluster_size = cluster_sizes[i];
                _cmov(!cluster_size, one, &cluster_size);
                centroids[i][j] = temp_centroids[j][i] / cluster_size;
            }
        }
    }

    free(cluster_sizes);
    for(int i = 0; i < dim; i++)
        free(temp_centroids[i]);
    free(temp_centroids);

    if (fabs(curr_total_dist - prev_total_dist) > error)
        return 0;
    else
        return 1;
}


int main(){

    srand(seed);

    float error = 1e-4;
    int num_iters = 20;

    /* Initialize all data points */
    float** data_points = (float**) malloc(sizeof(float*) * num_points);
    for(int i = 0; i < num_points; i++){
        data_points[i] = (float*) malloc(sizeof(float) * dim);
        for(int j = 0; j < dim; j++)
            data_points[i][j] = (float)rand() / RAND_MAX * max_val;
    }

    /* Initialize all centroids */
    float** centroids = (float**) malloc(sizeof(float*) * num_clusters);
    for(int i = 0; i < num_clusters; i++)
        centroids[i] = (float*) malloc(sizeof(float) * dim);

    /* Initialize all data labels */
    int* data_labels = (int*) malloc(sizeof(int) * num_points);

    /* Run k-means */
    int converge = Kmeans(data_points, error, centroids, data_labels, num_iters);

    /* Print if converge */
    if (converge)
        printf("Below error\n");
    else
        printf("Above error\n");

    /*[> Print all centroids <]*/
    /*for(int i = 0; i < num_clusters; i++){*/
        /*printf("centroid %d is (", i);*/
        /*for(int j = 0; j < dim; j++)*/
            /*printf("%f,", centroids[i][j]);*/
        /*printf(")\n");*/
    /*}*/

    /*[> Print all data labels <]*/
    /*for(int i = 0; i < num_points; i++){*/
        /*printf("data point %d (", i);*/
        /*for(int j = 0; j < dim; j++)*/
            /*printf("%f,", data_points[i][j]);*/
        /*printf(") is in cluster %d; distance = %f\n", data_labels[i], distance(data_points[i], centroids[data_labels[i]], dim));*/
    /*}*/

    /* Free data */
    for(int i = 0; i < num_points; i++)
        free(data_points[i]);
    free(data_points);
    for(int i = 0; i < num_clusters; i++)
        free(centroids[i]);
    free(centroids);
    free(data_labels);

    return 0;
}
