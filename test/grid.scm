(define (make-grid start dims)
  (if (list? (car dims)) (set! dims (car dims)) #t)
  (let ((v (make-vector (car dims) start)))
    (if (not (null? (cdr dims)))
        (letrec ((loop (lambda (i)
                         (if (>= i (car dims))
                             #t
                             (begin
                               (vector-set! v i
                                            (make-grid start (cons (cdr dims) '())))
                               (loop (+ i 1)))))))
          (loop 0))
        #t)
    v))

(define (grid-ref g n)
  (if (list? (car n)) (set! n (car n)) #t)
  (if (null? (cdr n))
      (vector-ref g (car n))
      (grid-ref (vector-ref g (car n)) (cons (cdr n) '()))))

(define (grid-set! g v n)
  (if (list? (car n)) (set! n (car n)) #t)
  (if (null? (cdr n))
      (vector-set! g (car n) v)
      (grid-set! (vector-ref g (car n)) v (cons (cdr n) '()))))

(define t (make-grid 0 '((4 5 6))))
(define u (make-grid #f '(2 2)))

(and (equal? (grid-ref t '((2 2 3))) 0)
     (begin
       (grid-set! t '24 '((2 2 3)))
       (equal? (grid-ref t '((2 2 3))) 24))
     (begin
       (grid-set! t #t '(1 0))
       (equal? (grid-ref t '(1 0)) #t)))
